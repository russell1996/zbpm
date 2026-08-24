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
    // Prefer worktree via env AGENT_HUB_WORKTREE, else derive correctly:
    // tools/agent-hub -> ../../.. -> zbpm-dev (sibling of zbpm, not child)
    let targetDir = dir;
    const envWorktree = process.env.AGENT_HUB_WORKTREE;
    if (envWorktree && existsSync(envWorktree)) {
      targetDir = envWorktree;
    } else {
      const devWorktree = join(dir, '..', '..', '..', 'zbpm-dev');
      if (existsSync(devWorktree)) {
        targetDir = devWorktree;
      }
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
