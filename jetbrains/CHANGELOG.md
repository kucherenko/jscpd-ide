# Changelog

## 0.1.0

First release.

- Runs `jscpd --lsp` for the project through an LSP4J client of its own, with the binary from the settings, from PATH, or downloaded from the GitHub release and checked against `checksums.txt`.
- Highlights with a gutter icon per kind of clone, the faded style for dead code, and the server's code actions as intentions.
- Tool window with Clones, Dead code, Complexity and Migration; status bar item with the duplication percentage.
- Tools | jscpd | Compare Two Folders runs `--compare`, fills the Migration tab, opens the HTML map and re-runs on save.
