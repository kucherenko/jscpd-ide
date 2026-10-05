# Changelog

## 0.1.1

- Screenshots of the Clones, Dead code and Migration views in the README, which is also the extension's page on the Marketplace and Open VSX.
- Install links for the VS Code Marketplace and Open VSX.

## 0.1.0

First release.

- Runs `jscpd --lsp` for every open workspace folder, with the binary from `jscpd.path`, from `PATH`, or downloaded from the GitHub release and checked against `checksums.txt`.
- Diagnostics, hover and code actions from the server; gutter icons and overview-ruler marks colored by kind of clone.
- Views: Clones (by kind), Dead code (by category), Complexity (by file) and Migration (pairs from `jscpd --compare`).
- Status bar item with the duplication percentage.
- `jscpd: Compare two folders` runs `--compare` with the vendored folders ignored, shows the pairs in the Migration view, opens the HTML map in a webview and re-runs on save.
