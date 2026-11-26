# Architecture

The plugin is organised by feature: each tool or file type has its own Kotlin package and its own plugin
descriptor. Adding support for something new means adding one package and one descriptor, and including
the descriptor from `plugin.xml`.

`gowin.eda` follows `quartus` file for file (project, service, run, run configuration, actions, devices, pins,
reports, tool window), so that what is learnt in one applies to the other. It reuses two generic pieces of
`quartus`: `QuartusPins.nodes`, which expands the ports of a module into the bits a pin is assigned to, and
`QuartusFilteringComboBox`; they could move to `common` should a third vendor need them. Its reports are shown
in Quartus's report viewer: `GowinEdaReportDocument` reads them into a `QuartusReportDocument`, which
`QuartusReportView` takes a parser for.

## Packages

All under `src/main/kotlin/com/jotrorox/verilogtoolbox/`:

| Package          | What it holds                                                                              |
|------------------|--------------------------------------------------------------------------------------------|
| `common`         | Shared infrastructure: `.f` file lists, the design definition, the OSS CAD Suite, GitHub releases, `BuildPlan` |
| `settings`       | Tool paths and options, and the **Settings → Tools → Verilog Toolbox** page                |
| `verilog`        | The Verilog/SystemVerilog language: file type, lexer, highlighting, folding, templates, module headers |
| `lsp`            | slang-server and Verible language servers, their downloads, and the design slang-server elaborates |
| `icarus`         | Icarus Verilog run configurations, testbench discovery and the test runner both simulators use |
| `testbench`      | The testbench generators: Verilog, and C++ for Verilator                                   |
| `waveform`       | GTKWave and Surfer                                                                         |
| `verilator`      | Verilator lint, and simulation built with CMake                                            |
| `yosys`          | Check Synthesis with Yosys                                                                 |
| `sby`            | SymbiYosys: the `.sby` language, its run configuration and the tasks in the test runner    |
| `quartus`        | Quartus Prime: projects, runs, pins, reports, the tool window                              |
| `quartus.tcl`    | The Tcl dialect of `.qsf`, `.qpf`, `.qip` and `.sdc` files                                 |
| `openfpgaloader` | Programming FPGAs with openFPGALoader                                                      |
| `gowin`          | Gowin `.cst` files and their checks, board presets, and the built-in Yosys/nextpnr/gowin_pack build |
| `gowin.eda`      | GOWIN EDA: `.gprj` projects, `gw_sh` and `programmer_cli` runs, pins, reports, the tool window |

Tests mirror the same packages under `src/test/kotlin/`. Plain unit tests are named `…Test`, tests that need
the IntelliJ platform `…PlatformTest`.

`verilog` depends on no other package of the plugin, and new features should only depend on `verilog`,
`common` and `settings`, or on a family package such as `gowin` for the build they offer, as `openfpgaloader` does,
or on `waveform` for showing a waveform, as `icarus` and `sby` do. `settings` is the exception the other way round: the settings page brings the options
of every tool together, so it knows the feature packages, and so does `common.VerilogDesign`, which takes
the design from the selected file list or slang-server top level.

## Plugin descriptors

`src/main/resources/META-INF/plugin.xml` holds what belongs to the whole plugin: its id, description,
dependencies, settings page and notification group. Each feature registers its extensions and actions in
`META-INF/features/<feature>.xml`, which `plugin.xml` pulls in with

```xml
<xi:include href="/META-INF/features/<feature>.xml" xpointer="xpointer(/idea-plugin/*)"/>
```

The order of the includes is the order in which actions appended to the same menu appear.

## Adding a file type

`gowin` is the smallest complete example. A language for a constraint or settings file needs:

1. `…Language.kt`: the `Language` object, the `LanguageFileType`, the `PsiFile` and a `ParserDefinition`.
   The plugin's languages have no grammar; the parser makes a flat tree of the lexer's tokens.
2. `…Lexer.kt`: the token types and a hand-written `LexerBase`. Keep all of its state in `getState()`, so
   the editor can restart lexing at any token; a lexer test that restarts at every token checks this.
3. `…Highlighting.kt`: `TextAttributesKey`s that fall back to `DefaultLanguageHighlighterColors`, the
   `SyntaxHighlighter` and its factory, a `ColorSettingsPage` with a demo, the `Commenter` and, where it
   applies, a brace matcher and quote handler.
4. `META-INF/features/<feature>.xml` registering them, included from `plugin.xml`.
5. A lexer test and a platform test for the file type, highlighting and commenting.

## Builds before programming

The openFPGALoader run configuration programs a bitstream after an optional build, a `common.BuildPlan`:
a list of `BuildStep`s (command lines) run one after the other. `openfpgaloader.CommandBuild` runs the
project's own command, such as `make`; `gowin.GowinBuild` is the built-in flow for Gowin FPGAs. Another
FPGA family (iCE40 with `nextpnr-ice40` and `icepack`, ECP5 with `nextpnr-ecp5` and `ecppack`) would be a
package like `gowin`: board presets, a `BuildPlan`, detection of its constraint files in
`OpenFpgaLoaderProject.detect`, and a choice in `BuildMode`.

## Simulators

Icarus Verilog and Verilator share the test runner of `icarus`: a run is a `SimulationTestsProcessHandler` of
`SimulationTest`s, each a list of `SimulationStep`s (command lines) whose last one is the simulation and whose
output a `SimulationOutput` reads (`IcarusTestOutput`, `VerilatorTestOutput`). `VerilogTestbenches` and
`VerilogTestLocator` find testbenches and lead tree nodes back to them. The waveform viewers take any
`waveform.WaveformSource`, which both run configurations are, and so is a formal task of `sby` for its traces.
These pieces could move to a package of their own should a third simulator come.

A Verilator simulation is built without Verilator's Makefiles and `verilator-config.cmake`, which need `make`,
Python or Perl. `VerilatorSimulation` runs `verilator --cc --make json` (`--make cmake` before 5.030), and
`VerilatorCMakeProject` writes a plain CMake project from the list of generated files that `VerilatorManifest`
reads, with a custom command that verilates again when a source changes. `CppToolchain` finds CMake, Ninja and
on Windows MinGW, taking CLion's bundled copies when there are none; CLion's own API is not used, so that the
plugin depends on no CLion internals. `LoadCMakeProjectAction` hands the project to CLion through the id of its
Load CMake Project action.

## Adding a tool

A tool such as a simulator, linter or programmer usually needs a path setting in `settings.ToolchainSettings`
(found through `findInstalledExecutable` and the OSS CAD Suite when unset), a run configuration type and
producer, actions, and a console filter for its output. `openfpgaloader` and `quartus` show the pattern.
