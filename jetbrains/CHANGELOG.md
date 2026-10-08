# Changelog

## 0.1.2

- Runs on 2025.1 and 2025.2 again: opening the second copy of a clone beside the first used an editor API whose shape changed in 2025.3, so the Marketplace verifier marked the plugin incompatible with them.
- No calls to platform APIs scheduled for removal in 2026.1: the PATH lookup for the binary and opening the migration map in the browser use plain ones.

## 0.1.1

- Highlights and gutter icons show up: the annotator was registered under a language key the platform does not read, so 0.1.0 reported findings in the tool window only.
- Opening a finding from the tool window no longer refreshes the file system on the UI thread.

## 0.1.0

First release.

- Runs `jscpd --lsp` for the project through an LSP4J client of its own, with the binary from the settings, from PATH, or downloaded from the GitHub release and checked against `checksums.txt`.
- Highlights with a gutter icon per kind of clone, the faded style for dead code, and the server's code actions as intentions.
- Tool window with Clones, Dead code, Complexity and Migration; status bar item with the duplication percentage.
- Tools | jscpd | Compare Two Folders runs `--compare`, fills the Migration tab, opens the HTML map and re-runs on save.
