// With JSCPD_SCREENSHOT=<file> set, opens the jscpd side bar on the fixture,
// waits for the views to fill, and takes a screenshot of the window
// (macOS `screencapture`, Linux `import` from ImageMagick). Skipped otherwise.
import * as path from 'path';
import { execSync } from 'child_process';
import * as vscode from 'vscode';
import type { JscpdApi } from '../../extension';

const EXTENSION_ID = 'kucherenko.jscpd';

suite('screenshot', () => {
  test('the side bar on the fixture', async function () {
    const target = process.env.JSCPD_SCREENSHOT;
    if (!target) {
      this.skip();
      return;
    }
    const ext = vscode.extensions.getExtension<JscpdApi>(EXTENSION_ID);
    const exported = await ext!.activate();
    await exported.ready();
    const folder = vscode.workspace.workspaceFolders![0];
    await vscode.workspace.getConfiguration('jscpd').update('analyses.deadCode', true, vscode.ConfigurationTarget.Global);
    await vscode.workspace.getConfiguration('jscpd').update('analyses.complexity', true, vscode.ConfigurationTarget.Global);
    await vscode.workspace.getConfiguration('jscpd').update('analyses.similarFunctions', true, vscode.ConfigurationTarget.Global);
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(path.join(folder.uri.fsPath, 'src', 'loans.js')));
    await vscode.window.showTextDocument(doc, { viewColumn: vscode.ViewColumn.One });
    await vscode.commands.executeCommand('workbench.view.extension.jscpd');
    await vscode.commands.executeCommand('workbench.actions.view.problems');
    await new Promise((r) => setTimeout(r, 6000));
    await vscode.commands.executeCommand('jscpd.clones.focus');
    await new Promise((r) => setTimeout(r, 1500));
    const shoot = (file: string) => execSync(process.platform === 'darwin' ? `screencapture -x "${file}"` : `import -window root "${file}"`);
    shoot(target);

    // The migration view and the map on compare-demo (needs the embedding model).
    const demo = path.resolve(folder.uri.fsPath, '..', 'compare-demo');
    const comparison = await vscode.commands.executeCommand('jscpd.compareFolders', path.join(demo, 'python'), path.join(demo, 'typescript'));
    if (comparison) {
      await vscode.commands.executeCommand('jscpd.migration.focus');
      await vscode.commands.executeCommand('jscpd.compare.openMap');
      await new Promise((r) => setTimeout(r, 2500));
      shoot(target.replace(/\.png$/, '-migration.png'));
    }
    await vscode.workspace.getConfiguration('jscpd').update('analyses.deadCode', undefined, vscode.ConfigurationTarget.Global);
    await vscode.workspace.getConfiguration('jscpd').update('analyses.complexity', undefined, vscode.ConfigurationTarget.Global);
    await vscode.workspace.getConfiguration('jscpd').update('analyses.similarFunctions', undefined, vscode.ConfigurationTarget.Global);
  });
});
