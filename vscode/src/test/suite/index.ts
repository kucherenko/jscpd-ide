// Mocha entry point inside the extension host. Failures also go to
// out/test/suite/failures.log, because the host's stdout can be cut off
// when the window closes right after the run.
import * as path from 'path';
import * as fs from 'fs';
import Mocha from 'mocha';

export function run(): Promise<void> {
  const mocha = new Mocha({ ui: 'tdd', color: true, timeout: 120000 });
  const dir = __dirname;
  const failuresLog = path.join(dir, 'failures.log');
  fs.rmSync(failuresLog, { force: true });
  for (const file of fs.readdirSync(dir)) {
    if (file.endsWith('.test.js')) {
      mocha.addFile(path.join(dir, file));
    }
  }
  return new Promise((resolve, reject) => {
    const runner = mocha.run((failures) => {
      // Give the reporter's last lines a moment to leave the pipe.
      setTimeout(() => (failures ? reject(new Error(`${failures} tests failed`)) : resolve()), 1000);
    });
    runner.on('fail', (test, error: Error) => {
      fs.appendFileSync(failuresLog, `${test.fullTitle()}\n${error.stack ?? error.message}\n\n`);
    });
  });
}
