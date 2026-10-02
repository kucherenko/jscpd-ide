// Downloads VS Code and runs the integration suite against fixtures/lsp-demo.
import * as path from 'path';
import { runTests } from '@vscode/test-electron';

async function main(): Promise<void> {
  const extensionDevelopmentPath = path.resolve(__dirname, '../..');
  const extensionTestsPath = path.resolve(__dirname, './suite/index');
  const workspace = path.resolve(extensionDevelopmentPath, '../fixtures/lsp-demo');
  try {
    await runTests({
      extensionDevelopmentPath,
      extensionTestsPath,
      launchArgs: [workspace, '--disable-extensions', '--disable-workspace-trust'],
    });
  } catch (error) {
    console.error('the tests failed', error);
    process.exit(1);
  }
}

void main();
