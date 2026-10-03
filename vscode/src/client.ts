// The language client around `jscpd --lsp`, and the settings it sends.
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions } from 'vscode-languageclient/node';
import { shellArg, spawnSpec } from './binary';

/**
 * The editor's settings as the server takes them: the keys of `.jscpd.json`,
 * with the `lsp` section built from the analysis toggles.
 *
 * The server reads each project's `.jscpd.json` and merges these on top, so
 * only what the user switched is sent: an analysis left off here keeps
 * whatever the project says, and a value at its default does not override
 * the project's own.
 */
export function serverSettings(): Record<string, unknown> {
  const cfg = vscode.workspace.getConfiguration('jscpd');
  const lsp: Record<string, Record<string, unknown> | boolean> = {};
  const section = (key: string): Record<string, unknown> => {
    if (typeof lsp[key] !== 'object') {
      lsp[key] = {};
    }
    return lsp[key] as Record<string, unknown>;
  };
  if (!cfg.get<boolean>('analyses.clones', true)) {
    section('clones').enabled = false;
  }
  const warningTokens = cfg.get<number | null>('clones.warningTokens', null);
  if (warningTokens != null && warningTokens > 0) {
    section('clones').warningTokens = warningTokens;
  }
  if (cfg.get<boolean>('analyses.similarFunctions', false)) {
    section('ast').enabled = true;
  }
  const similarity = cfg.get<number>('similarFunctions.similarity', 0.85);
  if (similarity !== 0.85) {
    section('ast').similarity = similarity;
  }
  if (cfg.get<boolean>('analyses.semantic', false)) {
    section('semantic').enabled = true;
  }
  if (cfg.get<boolean>('analyses.deadCode', false)) {
    section('deadCode').enabled = true;
  }
  if (cfg.get<boolean>('analyses.complexity', false)) {
    section('complexity').enabled = true;
  }
  const functionLimit = cfg.get<number>('complexity.functionLimit', 15);
  if (functionLimit !== 15) {
    section('complexity').functionLimit = functionLimit;
  }
  if (cfg.get<boolean>('allFiles', false)) {
    lsp.allFiles = true;
  }
  const passthrough = cfg.get<Record<string, unknown>>('settings', {});
  const passthroughLsp = typeof passthrough.lsp === 'object' && passthrough.lsp ? (passthrough.lsp as Record<string, unknown>) : {};
  return { ...passthrough, lsp: { ...passthroughLsp, ...lsp } };
}

export function createClient(
  command: string,
  args: string[],
  output: vscode.LogOutputChannel,
  trace: vscode.LogOutputChannel,
  onAnalysisDone: () => void,
): LanguageClient {
  // No explicit transport: with TransportKind.stdio the client would add a
  // `--stdio` argument, which jscpd does not take. Plain stdio is the default.
  const spec = spawnSpec(command);
  const serverOptions: ServerOptions = {
    command: spec.command,
    args: ['--lsp', ...args].map((a) => shellArg(a, spec.shell)),
    options: { shell: spec.shell },
  };
  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: 'file' }],
    initializationOptions: serverSettings(),
    outputChannel: output,
    traceOutputChannel: trace,
    diagnosticCollectionName: 'jscpd',
    progressOnInitialization: true,
    middleware: {
      // Each analysis reports progress; when one ends, its findings may be
      // in files that are not open, so the views ask for the reports again.
      handleWorkDoneProgress: (token, params, next) => {
        if (params.kind === 'end') {
          onAnalysisDone();
        }
        next(token, params);
      },
    },
  };
  return new LanguageClient('jscpd', 'jscpd', serverOptions, clientOptions);
}
