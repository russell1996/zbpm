import { execSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

export function parseTaskFromBranch(branch: string): string | null {
  if (!branch || branch === 'HEAD') return null;
  // feat/<wo-id>-<slug> -> WO-<ID>
  // e.g. feat/acl-1-split-read-resolver -> WO-ACL-1
  //      feat/sec-53-audit-housekeeping -> WO-SEC-53
  //      feat/perf-5-publish-coverage -> WO-PERF-5
  const m = branch.match(/^feat\/([a-z]+-\d+)-/i) || branch.match(/^feat\/([a-z]+-\d+)$/i);
  if (m) {
    return `WO-${m[1].toUpperCase()}`;
  }
  // Fallback: try to extract like feat/acl-1-... -> WO-ACL-1
  const m2 = branch.match(/^feat\/([a-z]+)-(\d+)(?:-|$)/i);
  if (m2) {
    return `WO-${m2[1].toUpperCase()}-${m2[2]}`;
  }
  return null;
}

export function getCurrentTask(cwd?: string): string | null {
  const dir = cwd || process.cwd();
  // Try to find git repo: check if .git exists or use git rev-parse
  try {
    // Prefer worktree ../zbpm-dev if exists (as per project), else cwd
    let targetDir = dir;
    // If cwd is tools/agent-hub, try to find zbpm-dev worktree
    const devWorktree = join(dir, '..', '..', 'zbpm-dev');
    const altDevWorktree = '/c/Users/1/vscode/projects/zorro/zbpm-dev';
    if (existsSync(devWorktree) && existsSync(join(devWorktree, '.git'))) {
      targetDir = devWorktree;
    } else if (existsSync(altDevWorktree)) {
      targetDir = altDevWorktree;
    }
    const branch = execSync('git rev-parse --abbrev-ref HEAD', {
      cwd: targetDir,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    return parseTaskFromBranch(branch);
  } catch {
    return null;
  }
}
