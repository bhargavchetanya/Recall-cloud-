import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs';
import { runTests } from '@vscode/test-electron';

/** Prefixes of temporary folders this run creates: the VS Code profile and the live test's throwaway repos. */
const TEMP_PREFIXES = ['recall-vscode-', 'recall-live-'];

async function main(): Promise<void> {
  const extensionDevelopmentPath = path.resolve(__dirname, '../../../');
  const extensionTestsPath = path.resolve(__dirname, './suite/index');
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'recall-vscode-'));
  let failed = false;
  try {
    await runTests({
      version: process.env.VSCODE_TEST_VERSION ?? 'stable',
      extensionDevelopmentPath,
      extensionTestsPath,
      // basic password store: headless Linux CI has no keyring for SecretStorage.
      launchArgs: ['--disable-extensions', '--password-store=basic', `--user-data-dir=${userDataDir}`],
    });
  } catch (err) {
    console.error('Integration tests failed', err);
    failed = true;
  } finally {
    // VS Code has exited here, so nothing holds these folders any more.
    removeTempFolders();
  }
  process.exit(failed ? 1 : 0);
}

function removeTempFolders(): void {
  for (const name of fs.readdirSync(os.tmpdir())) {
    if (TEMP_PREFIXES.some((p) => name.startsWith(p))) {
      try {
        fs.rmSync(path.join(os.tmpdir(), name), { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
      } catch (err) {
        console.warn(`Could not remove ${name}: ${String(err)}`);
      }
    }
  }
}

void main();
