# jscpd for Zed

Copy/paste detection while you type. The extension starts [jscpd](https://jscpd.dev) as a language server for every worktree, so clones, dead symbols and over-complex functions show up as diagnostics in the buffer, in the Problems panel and in the scrollbar, with a code action that jumps to the other copy.

## What you get

- Clones as you edit: exact copies, renamed copies, near-miss copies, and, when turned on, functions with the same syntax-tree shape and semantic clones. The diagnostic code says the kind: `jscpd/duplicate-code`, `jscpd/renamed-code`, `jscpd/similar-code`, `jscpd/similar-function`, `jscpd/semantic-code`.
- Code actions on a clone: go to the other copy, or wrap the block in `jscpd:ignore-start` / `jscpd:ignore-end`.
- Dead code (`unused-file`, `unused-export`, `unused-symbol`, `unused-import`, `unused-member`) and complexity (`jscpd/complex-function`, `jscpd/complex-file`) when you turn them on.
- Hover on a finding for the details.

## Install

Until the extension is in Zed's registry: clone [jscpd-ide](https://github.com/kucherenko/jscpd-ide), run `zed: install dev extension` from the command palette and pick its `zed` folder. Zed builds the extension itself (it needs Rust and the `wasm32-wasip1` target).

The extension looks for `jscpd` 5.4.0 or newer in `PATH`. If it is missing or too old, the latest release build for your platform is downloaded from GitHub into the extension's folder and checked against the release's `checksums.txt`. To use a binary of your own:

```json
{
  "lsp": {
    "jscpd": {
      "binary": {
        "path": "/usr/local/bin/jscpd",
        "arguments": ["--lsp"]
      }
    }
  }
}
```

## Settings

The analyses are the `lsp` section of `.jscpd.json`, which you can also pass per machine as initialization options. Every other key of `.jscpd.json` works there too:

```json
{
  "lsp": {
    "jscpd": {
      "initialization_options": {
        "lsp": {
          "clones": { "enabled": true, "warningTokens": 100 },
          "ast": { "enabled": true, "similarity": 0.85 },
          "semantic": { "enabled": false },
          "deadCode": { "enabled": true },
          "complexity": { "enabled": true, "functionLimit": 15 },
          "allFiles": false
        },
        "minTokens": 70
      }
    }
  }
}
```

A project's own `.jscpd.json` is read by the server; its `lsp` section switches the analyses for that project.

To keep jscpd out of a language, set `language_servers` for it:

```json
{
  "languages": {
    "JSON": { "language_servers": ["!jscpd", "..."] }
  }
}
```

## Measuring a port with `jscpd --compare`

Zed extensions cannot add panels or commands, so the comparison runs as a task. Put this in `.zed/tasks.json` of the project, with the folder you port from and the folder you port to:

```json
[
  {
    "label": "jscpd: compare src with rust",
    "command": "jscpd --compare src rust -r console,html -o .jscpd-compare --ignore '**/node_modules/**,**/target/**,**/vendor/**,**/dist/**,**/build/**,**/.git/**' && open .jscpd-compare/jscpd-compare.html",
    "cwd": "$ZED_WORKTREE_ROOT",
    "reveal": "always"
  }
]
```

`task: spawn` runs it. The console report lists the pairs and what is still only on one side; the HTML map opens in the browser. On Linux use `xdg-open` in place of `open`. `--compare` needs the embedding model once: `jscpd --semantic-download` (548 MB).

## jscpd in the Agent Panel

`jscpd --mcp` is an MCP server with `check_duplication`, `get_statistics` and `check_current_directory` tools. Add it as a custom server:

```json
{
  "context_servers": {
    "jscpd": {
      "command": { "path": "jscpd", "args": ["--mcp"] }
    }
  }
}
```

## Development

```bash
cd zed
cargo build --release --target wasm32-wasip1
```

Then `zed: install dev extension` on this folder, and `zed --foreground` in a terminal shows the extension's log lines.
