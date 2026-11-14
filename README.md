# Verilog Toolbox

Verilog and SystemVerilog support for CLion.

## Features

- Syntax highlighting, commenting, brace and quote matching for `.v`, `.vh`, `.sv`, `.svh`
- Diagnostics, formatting and quick-fixes via the [Verible](https://github.com/chipsalliance/verible) language server
- Optional [slang-server](https://github.com/hudson-trading/slang-server) for navigation, hover and completion
- Run testbenches with [Icarus Verilog](https://github.com/steveicarus/iverilog) straight from the gutter
- Open waveforms in [GTKWave](https://gtkwave.sourceforge.net) or [Surfer](https://surfer-project.org)

Verible and Icarus Verilog can be downloaded from within the IDE if they're not installed.
Tool paths live in **Settings → Tools → Verilog Toolbox**.

## Development

```bash
./gradlew runIde   # start CLion with the plugin
./gradlew check    # run tests
```

Try it out on the sample project in [`examples/demo`](examples/demo).

## License

See [LICENSE](LICENSE).
