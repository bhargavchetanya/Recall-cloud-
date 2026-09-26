import * as path from 'path';
import Mocha from 'mocha';

/**
 * Runs the headless suite. With RECALL_LIVE_SERVER set (e.g. http://localhost:8080), also runs live.test.js,
 * which drives a real terminal against a real backend so the rows can be inspected in Postgres. With
 * RECALL_DEMO_INVITE set (the invite code from scripts/seed-demo.sh), also runs the docs/DEMO.md walkthrough.
 */
export function run(): Promise<void> {
  const mocha = new Mocha({ ui: 'bdd', color: true, timeout: 30_000 });
  if (process.env.RECALL_DEMO_INVITE) {
    // The walkthrough runs alone, in a fresh window, exactly as a person following docs/DEMO.md would.
    mocha.addFile(path.resolve(__dirname, './demo.test.js'));
  } else {
    mocha.addFile(path.resolve(__dirname, './extension.test.js'));
    if (process.env.RECALL_LIVE_SERVER) {
      mocha.addFile(path.resolve(__dirname, './live.test.js'));
    }
  }
  return new Promise((resolve, reject) => {
    mocha.run((failures) => (failures > 0 ? reject(new Error(`${failures} test(s) failed`)) : resolve()));
  });
}
