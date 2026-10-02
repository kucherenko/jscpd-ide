# jscpd for VS Code

Copy/paste detection while you type. The extension runs [jscpd](https://jscpd.dev) as a language server, so every clone, dead symbol or over-complex function shows up as a diagnostic in the file you are editing, with a gutter icon colored by kind, a code action that jumps to the other copy, and four views in the jscpd side bar.

## What you get

- Clones as you edit. Exact copies, renamed copies (same code, other identifiers), near-miss copies, functions with the same syntax-tree shape, and, with the embedding model, functions that do the same job in different code. Each kind has its own color and gutter icon.
- Code actions on a clone: go to the other copy, or wrap the block in `jscpd:ignore-start` / `jscpd:ignore-end`.
- Dead code (unused files, exports, symbols, imports and members) and complexity (functions and files over the limit), when you turn them on.
- The jscpd side bar: Clones grouped by kind, Dead code by category, Complexity by file, and Migration for a port.
- A status bar item with the duplication of the open projects.
- Migration: pick the folder you port from and the folder you port to, and `jscpd --compare` pairs their functions. The view lists every paired function with its similarity, the functions that are still only on one side, and the ones that are ready to port. The HTML map opens next to your code and refreshes when you save a file in either folder.

## Install

1. Install the extension.
2. The extension looks for `jscpd` in `PATH`. If it is not there it offers to download the release build for your platform from GitHub, checks it against the release's `checksums.txt`, and keeps it in the extension's storage. You can also point `jscpd.path` at a binary you installed yourself (`npm i -g jscpd`, `cargo install jscpd`, Homebrew, or the installer on [jscpd.dev](https://jscpd.dev)).

jscpd 5.4.0 or newer is needed for the language server.

## Settings

| Setting | Default | What it does |
|---|---|---|
| `jscpd.enable` | `true` | Start jscpd for the workspace. |
| `jscpd.path` | `""` | Path to the executable. Empty: look in `PATH`, then use or download a release. |
| `jscpd.download` | `ask` | `ask`, `always` or `never` download a release when nothing is installed. |
| `jscpd.version` | `latest` | The release to download, `latest` or a tag. |
| `jscpd.analyses.clones` | `true` | Exact, renamed and near-miss copies. |
| `jscpd.analyses.similarFunctions` | `false` | Functions with the same syntax-tree shape (JavaScript, TypeScript). Off: as the project says. |
| `jscpd.analyses.semantic` | `false` | Semantic clones through the embedding model. Off: as the project says. |
| `jscpd.analyses.deadCode` | `false` | Unused files, exports, symbols, imports and members. Off: as the project says. |
| `jscpd.analyses.complexity` | `false` | Functions and files over the complexity limit. Off: as the project says. |
| `jscpd.allFiles` | `false` | Publish diagnostics for closed files too, so the Problems panel lists the whole project. |
| `jscpd.clones.warningTokens` | `null` | Clones of at least this many tokens are warnings, smaller ones information. |
| `jscpd.similarFunctions.similarity` | `0.85` | How much shape two functions must share. |
| `jscpd.complexity.functionLimit` | `15` | Cyclomatic complexity above which a function gets a diagnostic. |
| `jscpd.settings` | `{}` | Keys of `.jscpd.json` applied on top of every project's own config. |
| `jscpd.args` | `[]` | Extra arguments for `jscpd --lsp`. |
| `jscpd.decorations` | `true` | Gutter icons and overview-ruler marks. |
| `jscpd.compare.ignore` | vendored folders | Globs `--compare` leaves out of both sides. |
| `jscpd.compare.watch` | `true` | Run the last comparison again on save. |
| `jscpd.trace.server` | `off` | Log the LSP traffic. |

Each project keeps its own `.jscpd.json`; the server reads it, and the `lsp` section of that file sets the same analyses per project. The settings above are merged on top for all open projects, and only what you switch is sent: an analysis left off in the editor runs when the project turns it on, and values at their defaults leave the project's own in place.

## Commands

`jscpd: Rescan the workspace`, `jscpd: Restart the language server`, `jscpd: Show server output`, `jscpd: Download or update the jscpd binary`, `jscpd: Compare two folders (measure a port)`, `jscpd: Run the last comparison again`, `jscpd: Open the migration map`.

## Try it

Open `fixtures/lsp-demo` from the [jscpd-ide](https://github.com/kucherenko/jscpd-ide) repository. `src/loans.js` holds one function copied into `holds.js`, one built like a function in `holds.js`, and one with 19 branches; the README of the fixture says which diagnostic each gets. `fixtures/compare-demo` has a TypeScript module and its Python port for the Migration view.

## Development

```bash
cd vscode
npm install
npm run build        # or npm run watch
npm test             # downloads VS Code and runs the suite against fixtures/lsp-demo
npm run package      # writes jscpd-<version>.vsix
```

Press F5 in VS Code to open an extension host on the fixture.
