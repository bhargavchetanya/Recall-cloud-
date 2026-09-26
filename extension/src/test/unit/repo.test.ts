import * as path from 'path';
import { describe, expect, it, vi } from 'vitest';
import { RepoResolver } from '../../repo';

describe('RepoResolver', () => {
  const root = path.resolve('/work/shop');

  it('finds the nearest folder containing .git', async () => {
    const exists = vi.fn(async (p: string) => p === path.join(root, '.git'));
    const resolver = new RepoResolver(exists);

    expect(await resolver.repoName(path.join(root, 'src', 'api'))).toBe('shop');
  });

  it('returns undefined outside any repository and for no cwd', async () => {
    const resolver = new RepoResolver(async () => false);

    expect(await resolver.repoName(path.resolve('/tmp/scratch'))).toBeUndefined();
    expect(await resolver.repoName(undefined)).toBeUndefined();
  });

  it('caches per directory', async () => {
    const exists = vi.fn(async (p: string) => p === path.join(root, '.git'));
    const resolver = new RepoResolver(exists);

    await resolver.repoName(root);
    const calls = exists.mock.calls.length;
    await resolver.repoName(root);

    expect(exists.mock.calls.length).toBe(calls);
  });
});
