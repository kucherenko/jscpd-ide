# jscpd-ide

Editor extensions for [jscpd](https://jscpd.dev), the copy/paste detector. Each one runs `jscpd --lsp` for the open project and shows the clones, dead code and complexity in the editor, with one color per kind of clone, and helps to measure a port with `jscpd --compare`.

| Editor | Folder | State |
|---|---|---|
| VS Code (and Cursor, VSCodium, Windsurf) | [`vscode/`](vscode) | on the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=kucherenko.jscpd) and [Open VSX](https://open-vsx.org/extension/kucherenko/jscpd) |
| Zed | [`zed/`](zed) | works as a dev extension, registry submission pending |
| JetBrains IDEs | [`jetbrains/`](jetbrains) | on the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34744-jscpd), IDEs 2025.1 and newer |

What each editor can show differs. VS Code gets the full set: four views, gutter icons per kind, a status bar item and the migration map. JetBrains IDEs get the same through the plugin's own LSP client: a tool window with the four tabs, gutter icons, intentions, a status bar item and the compare dialog, in Community editions too. Zed has no extension-made panels, so there the extension is the language server (diagnostics, hover, code actions) plus a task for `jscpd --compare`.

[docs/spec.md](docs/spec.md) is the behaviour all three share: how the binary is found and downloaded, which diagnostic codes exist and what color each gets, the views, the commands and the settings.

## Try it on the fixtures

- `fixtures/lsp-demo`: a small library-loans module with one exact clone, one pair of similar functions, one function over the complexity limit, an unused import and an unused export. Its README says which diagnostic each file gets.
- `fixtures/compare-demo`: a TypeScript module and its Python port, for the Migration view.

Both are copied from the jscpd repository, where they are also the smoke corpus for the language server.

## License

MIT
