# jscpd for JetBrains IDEs

Copy/paste detection while you type, in IntelliJ IDEA, WebStorm, PyCharm, RustRover, GoLand, CLion, PhpStorm, Rider and the other IDEs on the IntelliJ platform, Community editions included. The plugin runs [jscpd](https://jscpd.dev) as a language server through its own LSP client (LSP4J), so it does not need the platform's paid-only LSP API.

## What you get

- Clones as you edit: exact copies, renamed copies, near-miss copies, functions with the same syntax-tree shape, and semantic clones when the embedding model is there. Each kind has its own gutter icon; the highlight carries the message and the other location.
- Intentions on a clone (Alt+Enter): go to the other copy, or wrap the block in `jscpd:ignore-start` / `jscpd:ignore-end`. A click on the gutter icon goes to the other copy.
- Dead code shown in the faded "unused" style, and complexity highlights, when the analyses are on.
- The jscpd tool window: Clones by kind, Dead code by category, Complexity by file, and Migration with the pairs of a `jscpd --compare` run. Double click opens a finding, both sides of a clone side by side.
- A status bar item with the duplication of the open project.
- Tools | jscpd: rescan, restart, compare two folders, open the migration map, download the binary.

## Install

Until the plugin is on the Marketplace: build it (below) and install `build/distributions/jscpd-jetbrains-0.1.0.zip` through Settings | Plugins | ⚙ | Install Plugin from Disk.

The plugin looks for `jscpd` 5.4.0 or newer in `PATH`. If it is missing, a notification offers to download the release build for your platform from GitHub into the IDE's system folder, checked against the release's `checksums.txt`. Settings | Tools | jscpd takes a path to a binary of your own.

## Settings

Settings | Tools | jscpd has the same switches as the VS Code extension: the five analyses, the warning threshold in tokens, the similarity of functions, the complexity limit, extra `.jscpd.json` keys as JSON, extra arguments for `jscpd --lsp`, gutter icons, and the folders `--compare` ignores. A project's own `.jscpd.json` is read by the server, and its `lsp` section switches the analyses for that project.

## Measuring a port

Tools | jscpd | Compare Two Folders asks for the folder you port from and the folder you port to, runs `jscpd --compare` with the vendored folders ignored, and fills the Migration tab: every paired function with its similarity, the functions that are ready to port, and the ones still only on one side. The HTML map opens in the browser. Saving a file in either folder runs the comparison again. `--compare` needs the embedding model once (548 MB); the plugin offers `jscpd --semantic-download` when it is missing.

## Development

```bash
cd jetbrains
./gradlew buildPlugin     # build/distributions/*.zip
./gradlew runIde          # an IDE with the plugin on fixtures/lsp-demo
./gradlew verifyPlugin    # the Plugin Verifier against the recommended IDEs
```

The build fetches a JDK 21 and IntelliJ IDEA 2025.3 on the first run.
