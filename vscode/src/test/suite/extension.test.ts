// The extension against the lsp-demo fixture: the server starts, the clone in
// loans.js is reported, the clones request answers, and the compare command exists.
import * as assert from 'assert';
import * as path from 'path';
import * as vscode from 'vscode';
import type { JscpdApi } from '../../extension';
import type { ClonesProject, Report } from '../../model';
import { untar } from '../../binary';
import { resolveProjectPath } from '../../model';

const EXTENSION_ID = 'kucherenko.jscpd';

async function api(): Promise<JscpdApi> {
  const ext = vscode.extensions.getExtension<JscpdApi>(EXTENSION_ID);
  assert.ok(ext, `${EXTENSION_ID} is not installed in the test host`);
  const exported = await ext.activate();
  await exported.ready();
  return exported;
}

async function waitFor<T>(what: string, probe: () => T | undefined, timeoutMs = 60000): Promise<T> {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const value = probe();
    if (value !== undefined) {
      return value;
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`timed out waiting for ${what}`);
}

suite('jscpd', () => {
  test('the language server starts', async () => {
    const exported = await api();
    assert.strictEqual(exported.state, 'running');
    assert.ok(exported.binary, 'no binary was found');
    assert.match(exported.binary.version, /^\d+\.\d+\.\d+/);
  });

  test('a duplicated file gets jscpd diagnostics', async () => {
    await api();
    const folder = vscode.workspace.workspaceFolders?.[0];
    assert.ok(folder, 'the fixture workspace is not open');
    const file = vscode.Uri.file(path.join(folder.uri.fsPath, 'src', 'loans.js'));
    const doc = await vscode.workspace.openTextDocument(file);
    await vscode.window.showTextDocument(doc);
    const diagnostics = await waitFor('jscpd diagnostics on loans.js', () => {
      const own = vscode.languages.getDiagnostics(file).filter((d) => d.source === 'jscpd');
      return own.length ? own : undefined;
    });
    const codes = diagnostics.map((d) => (typeof d.code === 'object' && d.code ? String(d.code.value) : String(d.code)));
    assert.ok(
      codes.some((c) => c.startsWith('jscpd/')),
      `expected a jscpd/* code, got ${codes.join(', ')}`,
    );
  });

  test('jscpd/clones answers with the project and its duplicates', async () => {
    const exported = await api();
    assert.ok(exported.client);
    const report = await exported.client.sendRequest<Report<ClonesProject>>('jscpd/clones');
    assert.ok(report.projects.length >= 1, 'no project in the clones report');
    const project = report.projects[0];
    assert.ok(project.roots.length >= 1);
    assert.ok(project.duplicates.length >= 1, 'the fixture should hold at least one clone');
    const first = project.duplicates[0];
    const resolved = resolveProjectPath(project, first.firstFile.name);
    assert.ok(path.isAbsolute(resolved), `resolved path should be absolute, got ${resolved}`);
  });

  test('the commands are registered', async () => {
    await api();
    const commands = await vscode.commands.getCommands(true);
    for (const id of ['jscpd.rescan', 'jscpd.restart', 'jscpd.compare', 'jscpd.compare.openMap', 'jscpd.downloadBinary']) {
      assert.ok(commands.includes(id), `${id} is not registered`);
    }
  });

  test('untar reads a plain ustar archive', () => {
    const header = Buffer.alloc(512);
    header.write('jscpd', 0, 'utf8');
    header.write('0000755\0', 100, 'utf8');
    header.write('0000000\0', 108, 'utf8');
    header.write('0000000\0', 116, 'utf8');
    const body = Buffer.from('#!/bin/sh\necho jscpd 5.4.0\n');
    header.write(body.length.toString(8).padStart(11, '0') + '\0', 124, 'utf8');
    header.write('00000000000\0', 136, 'utf8');
    header[156] = '0'.charCodeAt(0);
    header.write('ustar\0', 257, 'utf8');
    header.write('00', 263, 'utf8');
    const padded = Buffer.alloc(Math.ceil(body.length / 512) * 512);
    body.copy(padded);
    const archive = Buffer.concat([header, padded, Buffer.alloc(1024)]);
    const files = untar(archive);
    assert.strictEqual(files.get('jscpd')?.toString('utf8'), body.toString('utf8'));
  });
});
