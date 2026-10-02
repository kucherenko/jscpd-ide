# jscpd-ide

Editor extensions for [jscpd](https://jscpd.dev), the copy/paste detector. Each one runs `jscpd --lsp` for the open project and shows the clones, dead code and complexity in the editor, with one color per kind of clone, and helps to measure a port with `jscpd --compare`.

| Editor | Folder | State |
|---|---|---|
| VS Code (and Cursor, VSCodium, Windsurf) | [`vscode/`](vscode) | works, first release in progress |
| Zed | [`zed/`](zed) | works as a dev extension, registry submission pending |
| JetBrains IDEs | [`jetbrains/`](jetbrains) | planned |

What each editor can show differs. VS Code gets the full set: four views, gutter icons per kind, a status bar item and the migration map. Zed has no extension-made panels, so there the extension is the language server (diagnostics, hover, code actions) plus a task for `jscpd --compare`. JetBrains will get its own tool window, annotator and intentions.

[docs/spec.md](docs/spec.md) is the behaviour all three share: how the binary is found and downloaded, which diagnostic codes exist and what color each gets, the views, the commands and the settings.

## Try it on the fixtures

- `fixtures/lsp-demo`: a small library-loans module with one exact clone, one pair of similar functions, one function over the complexity limit, an unused import and an unused export. Its README says which diagnostic each file gets.
- `fixtures/compare-demo`: a TypeScript module and its Python port, for the Migration view.

Both are copied from the jscpd repository, where they are also the smoke corpus for the language server.

## License

MIT
