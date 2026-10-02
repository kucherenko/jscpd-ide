// The language client around `jscpd --lsp`, and the settings it sends.
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions } from 'vscode-languageclient/node';

/**
 * The editor's settings as the server takes them: the keys of `.jscpd.json`,
 * with the `lsp` section built from the analysis toggles.
 */
export function serverSettings(): Record<string, unknown> {
  const cfg = vscode.workspace.getConfiguration('jscpd');
  const warningTokens = cfg.get<number | null>('clones.warningTokens', null);
  const lsp: Record<string, unknown> = {
    clones: { enabled: cfg.get<boolean>('analyses.clones', true), ...(warningTokens == null ? {} : { warningTokens }) },
    ast: { enabled: cfg.get<boolean>('analyses.similarFunctions', false), similarity: cfg.get<number>('similarFunctions.similarity', 0.85) },
    semantic: { enabled: cfg.get<boolean>('analyses.semantic', false) },
    deadCode: { enabled: cfg.get<boolean>('analyses.deadCode', false) },
    complexity: { enabled: cfg.get<boolean>('analyses.complexity', false), functionLimit: cfg.get<number>('complexity.functionLimit', 15) },
    allFiles: cfg.get<boolean>('allFiles', false),
  };
  return { ...cfg.get<Record<string, unknown>>('settings', {}), lsp };
}

export function createClient(command: string, args: string[], output: vscode.LogOutputChannel, trace: vscode.LogOutputChannel): LanguageClient {
  // No explicit transport: with TransportKind.stdio the client would add a
  // `--stdio` argument, which jscpd does not take. Plain stdio is the default.
  const serverOptions: ServerOptions = {
    command,
    args: ['--lsp', ...args],
  };
  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: 'file' }],
    initializationOptions: serverSettings(),
    outputChannel: output,
    traceOutputChannel: trace,
    diagnosticCollectionName: 'jscpd',
    progressOnInitialization: true,
  };
  return new LanguageClient('jscpd', 'jscpd', serverOptions, clientOptions);
}
