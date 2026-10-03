// Measuring a port with `jscpd --compare`: pick two folders, run the comparison,
// show the pairs in the Migration view and the map in a webview.
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { execFile } from 'child_process';
import { shellArg, spawnSpec } from './binary';
import { CompareReport } from './model';
import { Comparison } from './views';

const SOURCE_KEY = 'jscpd.compare.source';
const TARGET_KEY = 'jscpd.compare.target';

/** The deepest folder that holds both paths. */
export function commonParent(a: string, b: string): string {
  const pa = path.resolve(a).split(path.sep);
  const pb = path.resolve(b).split(path.sep);
  const shared: string[] = [];
  for (let i = 0; i < Math.min(pa.length, pb.length) && pa[i] === pb[i]; i++) {
    shared.push(pa[i]);
  }
  return shared.join(path.sep) || path.parse(path.resolve(a)).root;
}

export class Migration implements vscode.Disposable {
  private last: Comparison | undefined;
  private panel: vscode.WebviewPanel | undefined;
  private timer: NodeJS.Timeout | undefined;
  private running = false;
  private readonly disposables: vscode.Disposable[] = [];
  readonly onDidChange: vscode.Event<Comparison | undefined>;
  private readonly changed = new vscode.EventEmitter<Comparison | undefined>();

  constructor(
    private readonly ctx: vscode.ExtensionContext,
    private readonly log: vscode.OutputChannel,
    private readonly binary: () => string | undefined,
  ) {
    this.onDidChange = this.changed.event;
    this.disposables.push(
      vscode.workspace.onDidSaveTextDocument((doc) => this.onSaved(doc)),
      this.changed,
    );
    void vscode.commands.executeCommand('setContext', 'jscpd.hasComparison', false);
  }

  get current(): Comparison | undefined {
    return this.last;
  }

  /** Asks for the two folders, then runs the comparison. */
  async compare(): Promise<void> {
    const source = await this.pickFolder('Source: the folder you port from', this.ctx.workspaceState.get<string>(SOURCE_KEY));
    if (!source) {
      return;
    }
    const target = await this.pickFolder('Target: the folder you port to', this.ctx.workspaceState.get<string>(TARGET_KEY));
    if (!target) {
      return;
    }
    if (source === target || source.startsWith(target + path.sep) || target.startsWith(source + path.sep)) {
      vscode.window.showErrorMessage('The two folders must not overlap: pick the source and the target of the port.');
      return;
    }
    await this.ctx.workspaceState.update(SOURCE_KEY, source);
    await this.ctx.workspaceState.update(TARGET_KEY, target);
    await this.run(source, target, true);
  }

  /** Compares two given folders without asking; for tests and other extensions. */
  async compareFolders(source: string, target: string): Promise<Comparison | undefined> {
    await this.ctx.workspaceState.update(SOURCE_KEY, source);
    await this.ctx.workspaceState.update(TARGET_KEY, target);
    await this.run(source, target, false);
    return this.last;
  }

  async rerun(): Promise<void> {
    if (!this.last) {
      await this.compare();
      return;
    }
    await this.run(this.last.source, this.last.target, true);
  }

  private async pickFolder(title: string, remembered: string | undefined): Promise<string | undefined> {
    const folders = vscode.workspace.workspaceFolders ?? [];
    const items: (vscode.QuickPickItem & { fsPath?: string })[] = [];
    if (remembered && fs.existsSync(remembered)) {
      items.push({ label: `$(history) ${vscode.workspace.asRelativePath(remembered, true)}`, description: 'last time', fsPath: remembered });
    }
    for (const folder of folders) {
      items.push({ label: `$(root-folder) ${folder.name}`, description: folder.uri.fsPath, fsPath: folder.uri.fsPath });
      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(folder.uri.fsPath, { withFileTypes: true });
      } catch {
        // unreadable
      }
      for (const entry of entries) {
        if (entry.isDirectory() && !entry.name.startsWith('.') && entry.name !== 'node_modules') {
          const fsPath = path.join(folder.uri.fsPath, entry.name);
          items.push({ label: `$(folder) ${folder.name}/${entry.name}`, fsPath });
        }
      }
    }
    items.push({ label: '$(folder-opened) Browse…', description: 'any folder on disk' });
    const picked = await vscode.window.showQuickPick(items, { title, placeHolder: 'Pick a folder', matchOnDescription: true });
    if (!picked) {
      return undefined;
    }
    if (picked.fsPath) {
      return picked.fsPath;
    }
    const chosen = await vscode.window.showOpenDialog({ canSelectFolders: true, canSelectFiles: false, canSelectMany: false, title, openLabel: 'Select' });
    return chosen?.[0]?.fsPath;
  }

  private outDir(): string {
    return path.join((this.ctx.storageUri ?? this.ctx.globalStorageUri).fsPath, 'compare');
  }

  private async run(source: string, target: string, announce: boolean): Promise<void> {
    const command = this.binary();
    if (!command) {
      vscode.window.showErrorMessage('jscpd is not available; set jscpd.path or download it first.');
      return;
    }
    if (this.running) {
      return;
    }
    this.running = true;
    const outDir = this.outDir();
    fs.mkdirSync(outDir, { recursive: true });
    const ignore = vscode.workspace.getConfiguration('jscpd').get<string>('compare.ignore', '');
    // Run from the folders' common parent with relative paths, so the report
    // names the sides `python` and `typescript` rather than two long paths.
    const cwd = commonParent(source, target);
    const args = ['--compare', path.relative(cwd, source) || '.', path.relative(cwd, target) || '.', '-r', 'json,html', '-o', outDir, '--silent'];
    if (ignore) {
      args.push('--ignore', ignore);
    }
    this.log.appendLine(`$ ${command} ${args.join(' ')}  (in ${cwd})`);
    try {
      await vscode.window.withProgress(
        { location: announce ? vscode.ProgressLocation.Notification : vscode.ProgressLocation.Window, title: 'jscpd: comparing the two folders', cancellable: false },
        () => this.exec(command, args, cwd),
      );
      const report = JSON.parse(fs.readFileSync(path.join(outDir, 'jscpd-compare.json'), 'utf8')) as CompareReport;
      const htmlPath = path.join(outDir, 'jscpd-compare.html');
      this.last = { source, target, report, htmlPath: fs.existsSync(htmlPath) ? htmlPath : undefined, ranAt: new Date() };
      await vscode.commands.executeCommand('setContext', 'jscpd.hasComparison', true);
      this.changed.fire(this.last);
      if (this.panel) {
        this.showMap();
      }
      const src = report.code.sides[0];
      if (announce) {
        const choice = await vscode.window.showInformationMessage(
          `${src.percentage.toFixed(0)}%: ${src.matched} of ${src.functions} functions in ${path.basename(source)} have a counterpart in ${path.basename(target)}.`,
          'Open the map',
          'Show the view',
        );
        if (choice === 'Open the map') {
          this.showMap();
        } else if (choice === 'Show the view') {
          await vscode.commands.executeCommand('jscpd.migration.focus');
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.log.appendLine(message);
      if (/semantic-download|embedding model|model .*not (found|downloaded)/i.test(message)) {
        const choice = await vscode.window.showWarningMessage('jscpd --compare needs the embedding model (548 MB, downloaded once). Download it now?', 'Download', 'Not now');
        if (choice === 'Download') {
          await this.downloadModel(command);
          await this.run(source, target, announce);
        }
      } else {
        vscode.window.showErrorMessage(`jscpd --compare failed: ${message.split('\n').slice(-3).join(' ')}`);
      }
    } finally {
      this.running = false;
    }
  }

  private exec(command: string, args: string[], cwd: string): Promise<void> {
    const spec = spawnSpec(command);
    return new Promise((resolve, reject) => {
      execFile(spec.command, args.map((a) => shellArg(a, spec.shell)), { cwd, maxBuffer: 64 * 1024 * 1024, windowsHide: true, shell: spec.shell }, (error, stdout, stderr) => {
        if (stdout) {
          this.log.append(String(stdout));
        }
        if (stderr) {
          this.log.append(String(stderr));
        }
        if (error) {
          reject(new Error(String(stderr || stdout || error.message)));
        } else {
          resolve();
        }
      });
    });
  }

  private async downloadModel(command: string): Promise<void> {
    this.log.show(true);
    await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: 'jscpd: downloading the embedding model' }, () => this.exec(command, ['--semantic-download'], this.outDir()));
  }

  private onSaved(doc: vscode.TextDocument): void {
    if (!this.last || !vscode.workspace.getConfiguration('jscpd').get<boolean>('compare.watch', true)) {
      return;
    }
    const file = doc.uri.fsPath;
    const inside = (dir: string) => file === dir || file.startsWith(dir + path.sep);
    if (!inside(this.last.source) && !inside(this.last.target)) {
      return;
    }
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.timer = setTimeout(() => {
      if (this.last) {
        void this.run(this.last.source, this.last.target, false);
      }
    }, 2000);
  }

  /** The HTML migration map jscpd wrote, in a webview. */
  showMap(): void {
    if (!this.last?.htmlPath || !fs.existsSync(this.last.htmlPath)) {
      vscode.window.showInformationMessage('No migration map yet: compare two folders first.');
      return;
    }
    if (!this.panel) {
      this.panel = vscode.window.createWebviewPanel('jscpd.migrationMap', 'Migration map', vscode.ViewColumn.Beside, {
        enableScripts: true,
        retainContextWhenHidden: true,
        localResourceRoots: [vscode.Uri.file(path.dirname(this.last.htmlPath))],
      });
      this.panel.onDidDispose(() => {
        this.panel = undefined;
      });
    }
    this.panel.title = `Migration map: ${path.basename(this.last.source)} → ${path.basename(this.last.target)}`;
    this.panel.webview.html = fs.readFileSync(this.last.htmlPath, 'utf8');
    this.panel.reveal(undefined, true);
  }

  dispose(): void {
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.panel?.dispose();
    for (const d of this.disposables) {
      d.dispose();
    }
  }
}
