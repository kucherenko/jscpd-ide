// jscpd for VS Code: starts `jscpd --lsp` for the workspace, shows what it finds
// in the editor and in the jscpd views, and measures a port with `--compare`.
import * as vscode from 'vscode';
import { DidChangeConfigurationNotification, LanguageClient, State } from 'vscode-languageclient/node';
import { Binary, DownloadPolicy, download, findBinary } from './binary';
import { createClient, serverSettings } from './client';
import { Decorations } from './decorations';
import { Migration } from './migration';
import { ClonesProject, ComplexityProject, DeadCodeProject, Report, SemanticProject, StatisticsProject } from './model';
import { ServerState, Status } from './status';
import { Location, NodeTreeProvider, buildClonesTree, buildComplexityTree, buildDeadCodeTree, buildMigrationTree } from './views';

/** What the extension exports, for tests and other extensions. */
export interface JscpdApi {
  readonly state: ServerState;
  readonly client: LanguageClient | undefined;
  readonly binary: Binary | undefined;
  /** Resolves when the server is running and the views have data. */
  ready(): Promise<void>;
}

let client: LanguageClient | undefined;
let binary: Binary | undefined;
let state: ServerState = 'starting';
let log: vscode.LogOutputChannel;
let trace: vscode.LogOutputChannel;
let status: Status;
let readyWaiters: { resolve: () => void; reject: (error: Error) => void }[] = [];

const clonesView = new NodeTreeProvider();
const deadCodeView = new NodeTreeProvider();
const complexityView = new NodeTreeProvider();
const migrationView = new NodeTreeProvider();
let clonesTree: vscode.TreeView<unknown>;
let deadCodeTree: vscode.TreeView<unknown>;
let complexityTree: vscode.TreeView<unknown>;

export async function activate(ctx: vscode.ExtensionContext): Promise<JscpdApi> {
  log = vscode.window.createOutputChannel('jscpd', { log: true });
  trace = vscode.window.createOutputChannel('jscpd (trace)', { log: true });
  status = new Status();
  const migration = new Migration(ctx, log, () => binary?.command);
  ctx.subscriptions.push(log, trace, status, migration, new Decorations(ctx));

  clonesTree = vscode.window.createTreeView('jscpd.clones', { treeDataProvider: clonesView, showCollapseAll: true });
  deadCodeTree = vscode.window.createTreeView('jscpd.deadCode', { treeDataProvider: deadCodeView, showCollapseAll: true });
  complexityTree = vscode.window.createTreeView('jscpd.complexity', { treeDataProvider: complexityView });
  const migrationTree = vscode.window.createTreeView('jscpd.migration', { treeDataProvider: migrationView, showCollapseAll: true });
  ctx.subscriptions.push(clonesTree, deadCodeTree, complexityTree, migrationTree);
  ctx.subscriptions.push(migration.onDidChange((c) => migrationView.setRoots(buildMigrationTree(c))));

  ctx.subscriptions.push(
    vscode.commands.registerCommand('jscpd.rescan', async () => {
      if (client?.state === State.Running) {
        await client.sendRequest('jscpd/rescan');
        await refresh();
      }
    }),
    vscode.commands.registerCommand('jscpd.restart', () => restart(ctx)),
    vscode.commands.registerCommand('jscpd.showOutput', () => log.show(true)),
    vscode.commands.registerCommand('jscpd.downloadBinary', async () => {
      const wanted = vscode.workspace.getConfiguration('jscpd').get<string>('version', 'latest');
      const got = await download(ctx, log, wanted);
      if (got) {
        vscode.window.showInformationMessage(`jscpd ${got.version} is ready.`);
        await restart(ctx);
      }
    }),
    vscode.commands.registerCommand('jscpd.compare', () => migration.compare()),
    vscode.commands.registerCommand('jscpd.compare.rerun', () => migration.rerun()),
    vscode.commands.registerCommand('jscpd.compareFolders', (source: string, target: string) => migration.compareFolders(source, target)),
    vscode.commands.registerCommand('jscpd.compare.openMap', () => migration.showMap()),
    vscode.commands.registerCommand('jscpd.openLocation', (loc: Location) => openLocation(loc, vscode.ViewColumn.Active)),
    vscode.commands.registerCommand('jscpd.openPair', async (a: Location, b: Location) => {
      await openLocation(a, vscode.ViewColumn.One);
      await openLocation(b, vscode.ViewColumn.Beside, true);
    }),
  );

  ctx.subscriptions.push(
    vscode.languages.onDidChangeDiagnostics(() => scheduleRefresh()),
    vscode.workspace.onDidChangeConfiguration(async (e) => {
      if (!e.affectsConfiguration('jscpd')) {
        return;
      }
      const needsRestart = ['jscpd.enable', 'jscpd.path', 'jscpd.args', 'jscpd.download', 'jscpd.version'].some((k) => e.affectsConfiguration(k));
      if (needsRestart) {
        await restart(ctx);
      } else if (client?.state === State.Running) {
        await client.sendNotification(DidChangeConfigurationNotification.type, { settings: { jscpd: serverSettings() } });
        await refresh();
      }
    }),
    vscode.workspace.onDidChangeWorkspaceFolders(() => restart(ctx)),
  );

  await start(ctx);

  return {
    get state() {
      return state;
    },
    get client() {
      return client;
    },
    get binary() {
      return binary;
    },
    ready: () =>
      new Promise<void>((resolve, reject) => {
        if (state === 'running') {
          resolve();
        } else if (state === 'starting') {
          readyWaiters.push({ resolve, reject });
        } else {
          reject(new Error(`jscpd is not running: ${state}`));
        }
      }),
  };
}

export async function deactivate(): Promise<void> {
  await client?.stop();
  client = undefined;
}

async function setState(next: ServerState, detail?: string): Promise<void> {
  state = next;
  status.setState(next, detail);
  await vscode.commands.executeCommand('setContext', 'jscpd.serverState', next);
  if (next !== 'starting') {
    const waiters = readyWaiters;
    readyWaiters = [];
    for (const w of waiters) {
      if (next === 'running') {
        w.resolve();
      } else {
        w.reject(new Error(`jscpd is not running: ${next}${detail ? ` (${detail})` : ''}`));
      }
    }
  }
}

async function start(ctx: vscode.ExtensionContext): Promise<void> {
  const cfg = vscode.workspace.getConfiguration('jscpd');
  if (!cfg.get<boolean>('enable', true)) {
    await setState('disabled');
    return;
  }
  if (!vscode.workspace.workspaceFolders?.length) {
    await setState('no-workspace');
    return;
  }
  await setState('starting');
  binary = await findBinary(ctx, log, cfg.get<string>('path', ''), cfg.get<DownloadPolicy>('download', 'ask'), cfg.get<string>('version', 'latest'));
  if (!binary) {
    log.appendLine('jscpd was not found: set jscpd.path, install it, or run "jscpd: Download or update the jscpd binary"');
    await setState('missing');
    return;
  }
  log.appendLine(`using jscpd ${binary.version} at ${binary.command} (${binary.source})`);
  client = createClient(binary.command, cfg.get<string[]>('args', []), log, trace, () => scheduleRefresh());
  client.onDidChangeState((e) => {
    if (e.newState === State.Stopped && state === 'running') {
      void setState('failed', 'jscpd stopped. Click for the output.');
    }
  });
  try {
    await client.start();
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    log.appendLine(`jscpd did not start: ${message}`);
    await setState('failed', `jscpd did not start: ${message}`);
    return;
  }
  await setState('running');
  await refresh();
}

async function restart(ctx: vscode.ExtensionContext): Promise<void> {
  if (client) {
    try {
      await client.stop();
    } catch {
      // the server may be gone already
    }
    client = undefined;
  }
  await start(ctx);
}

let refreshTimer: NodeJS.Timeout | undefined;

/** A refresh soon, with bursts of changes folded into one. */
function scheduleRefresh(): void {
  if (refreshTimer) {
    clearTimeout(refreshTimer);
  }
  refreshTimer = setTimeout(() => void refresh(), 700);
}

/** Asks the server for its reports and fills the views and the status bar. */
async function refresh(): Promise<void> {
  if (!client || client.state !== State.Running) {
    return;
  }
  try {
    const [clones, statistics] = await Promise.all([
      client.sendRequest<Report<ClonesProject>>('jscpd/clones'),
      client.sendRequest<Report<StatisticsProject>>('jscpd/statistics'),
    ]);
    // Every report is asked for: a project's own .jscpd.json may turn an
    // analysis on that the editor's settings leave alone, and the server
    // answers only for projects that run it.
    const [semantic, deadCodeReport, complexityReport] = await Promise.all([
      client.sendRequest<Report<SemanticProject>>('jscpd/semantic'),
      client.sendRequest<Report<DeadCodeProject>>('jscpd/deadCode'),
      client.sendRequest<Report<ComplexityProject>>('jscpd/complexity'),
    ]);
    const clonesTreeData = buildClonesTree(clones, semantic);
    clonesView.setRoots(clonesTreeData.roots);
    clonesTree.badge = clonesTreeData.count ? { value: clonesTreeData.count, tooltip: `${clonesTreeData.count} clones` } : undefined;
    await vscode.commands.executeCommand('setContext', 'jscpd.cloneCount', clonesTreeData.count);
    status.setStatistics(statistics, binary?.version);

    const dead = buildDeadCodeTree(deadCodeReport);
    deadCodeView.setRoots(dead.roots);
    deadCodeTree.badge = dead.count ? { value: dead.count, tooltip: `${dead.count} dead code findings` } : undefined;
    await vscode.commands.executeCommand('setContext', 'jscpd.hasDeadCode', dead.count > 0);

    const cx = buildComplexityTree(complexityReport);
    complexityView.setRoots(cx.roots);
    complexityTree.badge = cx.count ? { value: cx.count, tooltip: `${cx.count} files over the complexity bar` } : undefined;
    await vscode.commands.executeCommand('setContext', 'jscpd.hasComplexity', cx.roots.length > 0);
  } catch (error) {
    log.appendLine(`refresh failed: ${error instanceof Error ? error.message : String(error)}`);
  }
}

async function openLocation(loc: Location, column: vscode.ViewColumn, preserveFocus = false): Promise<void> {
  const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(loc.path));
  const start = new vscode.Position(Math.max(0, loc.startLine - 1), loc.startColumn ?? 0);
  const endLine = Math.max(0, Math.min(loc.endLine - 1, doc.lineCount - 1));
  const end = doc.lineAt(endLine).range.end;
  await vscode.window.showTextDocument(doc, { viewColumn: column, preserveFocus, selection: new vscode.Range(start, end), preview: true });
}
