import { promises as fs } from 'fs';
import * as path from 'path';

/**
 * Name of the git repository containing `cwd` (the folder holding `.git`), or undefined outside a repo.
 * Results are cached per directory because this runs after every command.
 */
export class RepoResolver {
  private readonly cache = new Map<string, string | undefined>();

  constructor(private readonly exists: (p: string) => Promise<boolean> = pathExists) {}

  async repoName(cwd: string | undefined): Promise<string | undefined> {
    if (!cwd) {
      return undefined;
    }
    if (this.cache.has(cwd)) {
      return this.cache.get(cwd);
    }
    let dir = path.resolve(cwd);
    let found: string | undefined;
    for (;;) {
      if (await this.exists(path.join(dir, '.git'))) {
        found = path.basename(dir);
        break;
      }
      const parent = path.dirname(dir);
      if (parent === dir) {
        break;
      }
      dir = parent;
    }
    this.cache.set(cwd, found);
    return found;
  }
}

async function pathExists(p: string): Promise<boolean> {
  try {
    await fs.stat(p);
    return true;
  } catch {
    return false;
  }
}
