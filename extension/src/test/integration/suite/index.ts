import * as path from 'path';
import Mocha from 'mocha';

/** Runs the headless suite: real VS Code terminals against a fake backend. */
export function run(): Promise<void> {
  const mocha = new Mocha({ ui: 'bdd', color: true, timeout: 30_000 });
  mocha.addFile(path.resolve(__dirname, './extension.test.js'));
  return new Promise((resolve, reject) => {
    mocha.run((failures) => (failures > 0 ? reject(new Error(`${failures} test(s) failed`)) : resolve()));
  });
}
