# Verilog Toolbox

Verilog and SystemVerilog support for CLion.

## Features

- Syntax highlighting, commenting, brace and quote matching for `.v`, `.vh`, `.sv`, `.svh`
- Diagnostics, navigation, hover, completion, rename and inlay hints via the
  [slang-server](https://github.com/hudson-trading/slang-server) language server, downloaded automatically
- Optional [Verible](https://github.com/chipsalliance/verible) language server for formatting and style lint
- Run testbenches with [Icarus Verilog](https://github.com/steveicarus/iverilog) straight from the gutter
- Open waveforms in [GTKWave](https://gtkwave.sourceforge.net) or [Surfer](https://surfer-project.org)

Tool paths live in **Settings → Tools → Verilog Toolbox**. Verible and Icarus Verilog can be downloaded
from there if they're not installed.

### Language server

slang-server (MIT licensed) is fetched from its GitHub releases the first time a Verilog file is opened,
unless one is set in the settings or already on your `PATH`. The automatic download can be switched off.

It works without configuration, checking each file on its own. To have it elaborate a whole design,
right-click a file and choose **Set as slang-server Top Level**, or a `.f` file list and
**Use as slang-server Build File**. Include directories, defines and other options go in
`.slang/server.json`, see the [slang-server documentation](https://hudson-trading.github.io/slang-server/start/config/).

## Development

```bash
./gradlew runIde   # start CLion with the plugin
./gradlew check    # run tests
```

Try it out on the sample project in [`examples/demo`](examples/demo).

## License

See [LICENSE](LICENSE).
