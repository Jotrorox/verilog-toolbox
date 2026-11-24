# Architecture

The plugin is organised by feature: each tool or file type has its own Kotlin package and its own plugin
descriptor. Adding support for something new means adding one package and one descriptor, and including
the descriptor from `plugin.xml`.

## Packages

All under `src/main/kotlin/com/jotrorox/verilogtoolbox/`:

| Package          | What it holds                                                                              |
|------------------|--------------------------------------------------------------------------------------------|
| `common`         | Shared infrastructure: `.f` file lists, the design definition, the OSS CAD Suite, GitHub releases |
| `settings`       | Tool paths and options, and the **Settings → Tools → Verilog Toolbox** page                |
| `verilog`        | The Verilog/SystemVerilog language: file type, lexer, highlighting, folding, templates, module headers |
| `lsp`            | slang-server and Verible language servers, their downloads, and the design slang-server elaborates |
| `icarus`         | Icarus Verilog run configurations, testbench discovery and the test runner                 |
| `testbench`      | The testbench generator                                                                    |
| `waveform`       | GTKWave and Surfer                                                                         |
| `verilator`      | Verilator lint                                                                             |
| `yosys`          | Check Synthesis with Yosys                                                                 |
| `quartus`        | Quartus Prime: projects, runs, pins, reports, the tool window                              |
| `quartus.tcl`    | The Tcl dialect of `.qsf`, `.qpf`, `.qip` and `.sdc` files                                 |
| `openfpgaloader` | Programming FPGAs with openFPGALoader                                                      |
| `gowin`          | Gowin physical constraint files (`.cst`)                                                   |

Tests mirror the same packages under `src/test/kotlin/`. Plain unit tests are named `…Test`, tests that need
the IntelliJ platform `…PlatformTest`.

`verilog` depends on no other package of the plugin, and new features should only depend on `verilog`,
`common` and `settings`. `settings` is the exception the other way round: the settings page brings the options
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

## Adding a tool

A tool such as a simulator, linter or programmer usually needs a path setting in `settings.ToolchainSettings`
(found through `findInstalledExecutable` and the OSS CAD Suite when unset), a run configuration type and
producer, actions, and a console filter for its output. `openfpgaloader` and `quartus` show the pattern.
