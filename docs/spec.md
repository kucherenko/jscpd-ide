# What every jscpd editor extension does

The three extensions (VS Code, Zed, JetBrains) share one behaviour. This page is the contract; the folders hold the code for each editor.

## The binary

The extension does not bundle jscpd. It finds one in this order:

1. The path in the extension's settings (`jscpd.path` in VS Code, `lsp.jscpd.binary.path` in Zed, the plugin settings page in JetBrains).
2. `jscpd` on the `PATH` (`jscpd.exe` and `jscpd.cmd` on Windows too).
3. A release build the extension downloaded earlier into its own storage, newest version first.
4. A download from the GitHub release of [kucherenko/jscpd](https://github.com/kucherenko/jscpd/releases): the asset `jscpd-<platform>.tar.gz` for `darwin-arm64`, `darwin-x64`, `linux-x64-gnu`, `linux-x64-musl`, `linux-arm64-gnu`, `linux-arm64-musl`, `windows-x64-msvc` or `windows-arm64-msvc`. The archive's SHA-256 is checked against the release's `checksums.txt` before anything is unpacked, and the binary must answer `--version` before it is kept.

Every candidate is run with `--version`; one that does not print a version is skipped. Download policy: ask (default), always, never.

jscpd 5.4.0 is the oldest release with `--lsp`.

## The language server

Started as `jscpd --lsp` plus the user's extra arguments, over stdio, one server per editor window with every workspace folder as a root. The server reads each project's `.jscpd.json` itself, so the extension never passes `--config` or paths.

Initialization options and `workspace/didChangeConfiguration` carry the keys of `.jscpd.json`. The server reads each project's own `.jscpd.json` and merges the editor's values on top, so an explicit `false` from the editor switches off what the project turned on. The extensions therefore send only what the user switched: an analysis turned on in the editor is sent as `enabled: true`, clones turned off as `enabled: false`, and everything else is left out so the project's config decides. Values at their defaults (similarity 0.85, function limit 15, no warning threshold) are left out for the same reason. With every toggle on, the section looks like this:

```json
{
  "lsp": {
    "clones":     { "warningTokens": 100 },
    "ast":        { "enabled": true, "similarity": 0.9 },
    "semantic":   { "enabled": true },
    "deadCode":   { "enabled": true },
    "complexity": { "enabled": true, "functionLimit": 20 },
    "allFiles":   true
  }
}
```

The views ask for every report whatever the editor's toggles say, because the server answers only for the projects that run an analysis.

Diagnostics come with `source: "jscpd"` and these codes:

| Code | Kind | Color |
|---|---|---|
| `jscpd/duplicate-code` | exact copy | orange `#eb6834` |
| `jscpd/renamed-code` | same code, other identifiers | amber `#eda100` |
| `jscpd/similar-code` | near-miss copy | blue `#3987e5` |
| `jscpd/similar-function` | same syntax-tree shape | blue `#3987e5` |
| `jscpd/semantic-code` | same job, different code | violet `#9085e9` |
| `jscpd/complex-function`, `jscpd/complex-file` | over the complexity limit | rose `#d55181` |
| `unused-file`, `unused-export`, `unused-symbol`, `unused-import`, `unused-member` | dead code | editor's "unnecessary" style |

Each kind gets a gutter icon in that color (SVGs in `vscode/resources/icons`, reused by the other editors) and the same color in the overview ruler or the scrollbar.

Code actions the server offers on a clone: go to the other copy / the similar function, and wrap the block in `jscpd:ignore-start` and `jscpd:ignore-end`. The server answers the command `jscpd.showLocation` with `window/showDocument`, so a client only needs to support that request.

## The side bar

Four views, filled from the server's custom requests:

| View | Request | Grouping |
|---|---|---|
| Clones | `jscpd/clones` (+ `jscpd/semantic` when on) | by kind, then one node per pair with both locations as children; the fragment in the tooltip |
| Dead code | `jscpd/deadCode` | by category; confidence in the description |
| Complexity | `jscpd/complexity` | files by complexity, the ones over the file limit in rose |
| Migration | the `jscpd-compare.json` of the last `--compare` | Code and Tests sections: paired functions per file, Ready to port, Only in source, Only in target |

Paths in `jscpd/clones` are relative to the project root; with several roots they start with the root's folder name. `jscpd/deadCode` and `jscpd/complexity` give absolute paths. Lines are 1-based in every report.

The views refresh after every diagnostics change (debounced) and after `jscpd/rescan`. A badge on the Clones view carries the count. The status bar shows the duplication percentage from `jscpd/statistics` (`duplicatedLines / lines` over all projects).

## Migration

`Compare two folders` asks for the source and the target (workspace folders, their subfolders, or any folder), remembers them per workspace, and runs

```
jscpd --compare <source> <target> -r json,html -o <storage> --ignore <globs> --silent
```

with the vendored folders (`node_modules`, `target`, `vendor`, `dist`, `build`, `.git`) ignored by default. The JSON fills the Migration view, the HTML opens as the migration map next to the code, and both refresh when a file in either folder is saved. If the embedding model is missing, the extension offers `jscpd --semantic-download` (548 MB, once).

## Commands

| Command | Does |
|---|---|
| Rescan the workspace | `jscpd/rescan`, then refresh the views |
| Restart the language server | stop and start again, picking up a new binary or arguments |
| Show server output | the log channel |
| Download or update the jscpd binary | download the release in `jscpd.version` |
| Compare two folders | the migration flow above |
| Run the last comparison again | same folders |
| Open the migration map | the HTML report |

## Settings

The same names in VS Code and JetBrains, under the editor's own prefix: `enable`, `path`, `download`, `version`, `analyses.clones`, `analyses.similarFunctions`, `analyses.semantic`, `analyses.deadCode`, `analyses.complexity`, `allFiles`, `clones.warningTokens`, `similarFunctions.similarity`, `complexity.functionLimit`, `settings` (raw `.jscpd.json` keys), `args`, `decorations`, `compare.ignore`, `compare.watch`.

Zed has no settings UI for extensions, so there the analyses are the `lsp` section itself, written as `lsp.jscpd.initialization_options`, and the binary is `lsp.jscpd.binary.path` with `arguments`. A `jscpd` on the PATH older than 5.4.0 (the npm v4 package, for instance) is skipped in favour of a downloaded release.

## What each editor cannot do

- Zed: no panels, commands, gutter icons or status items from an extension (slash commands were removed from Zed). The views and the migration flow are not available; `--compare` runs as a task and opens its HTML map in the browser.
- JetBrains Community IDEs: no platform LSP API, which is why the plugin carries its own client.
