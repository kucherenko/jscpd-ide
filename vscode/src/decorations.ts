// Gutter icons and overview-ruler marks for the server's diagnostics, by kind.
import * as vscode from 'vscode';

interface Style {
  icon: string;
  color: string;
}

const STYLES: Record<string, Style> = {
  'jscpd/duplicate-code': { icon: 'clone-exact', color: 'jscpd.exact' },
  'jscpd/renamed-code': { icon: 'clone-renamed', color: 'jscpd.renamed' },
  'jscpd/similar-code': { icon: 'clone-similar', color: 'jscpd.similar' },
  'jscpd/similar-function': { icon: 'clone-function', color: 'jscpd.similar' },
  'jscpd/semantic-code': { icon: 'clone-semantic', color: 'jscpd.semantic' },
  'jscpd/complex-function': { icon: 'complexity', color: 'jscpd.complexity' },
  'jscpd/complex-file': { icon: 'complexity', color: 'jscpd.complexity' },
};

export class Decorations implements vscode.Disposable {
  private readonly types = new Map<string, vscode.TextEditorDecorationType>();
  private readonly disposables: vscode.Disposable[] = [];
  private timer: NodeJS.Timeout | undefined;

  constructor(ctx: vscode.ExtensionContext) {
    for (const [code, style] of Object.entries(STYLES)) {
      this.types.set(
        code,
        vscode.window.createTextEditorDecorationType({
          gutterIconPath: vscode.Uri.joinPath(ctx.extensionUri, 'resources', 'icons', `${style.icon}.svg`),
          gutterIconSize: 'contain',
          overviewRulerColor: new vscode.ThemeColor(style.color),
          overviewRulerLane: vscode.OverviewRulerLane.Right,
        }),
      );
    }
    this.disposables.push(
      vscode.languages.onDidChangeDiagnostics(() => this.schedule()),
      vscode.window.onDidChangeVisibleTextEditors(() => this.schedule()),
      vscode.workspace.onDidChangeConfiguration((e) => {
        if (e.affectsConfiguration('jscpd.decorations')) {
          this.schedule();
        }
      }),
    );
    this.schedule();
  }

  private schedule(): void {
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.timer = setTimeout(() => this.apply(), 150);
  }

  private apply(): void {
    const enabled = vscode.workspace.getConfiguration('jscpd').get<boolean>('decorations', true);
    for (const editor of vscode.window.visibleTextEditors) {
      const ranges = new Map<string, vscode.Range[]>();
      if (enabled) {
        for (const d of vscode.languages.getDiagnostics(editor.document.uri)) {
          if (d.source !== 'jscpd') {
            continue;
          }
          const code = typeof d.code === 'object' && d.code ? String(d.code.value) : String(d.code ?? '');
          if (!this.types.has(code)) {
            continue;
          }
          const line = d.range.start.line;
          const list = ranges.get(code) ?? [];
          list.push(new vscode.Range(line, 0, line, 0));
          ranges.set(code, list);
        }
      }
      for (const [code, type] of this.types) {
        editor.setDecorations(type, ranges.get(code) ?? []);
      }
    }
  }

  dispose(): void {
    if (this.timer) {
      clearTimeout(this.timer);
    }
    for (const d of this.disposables) {
      d.dispose();
    }
    for (const type of this.types.values()) {
      type.dispose();
    }
  }
}
