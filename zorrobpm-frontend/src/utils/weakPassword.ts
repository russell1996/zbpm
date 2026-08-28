/**
 * WO-ACL-19 (P2): single source of truth for weak-password detection on the frontend.
 * Mirrors the backend {@code AdminPasswordValidator.isWeak} blocklist so the rule is identical
 * everywhere a password is set (MyProfile, ResetPassword, AcceptInvitation).
 */
const weakBlocklist = new Set([
  'admin',
  'password',
  'zorrodev',
  '123456',
  'qwerty',
  'letmein',
  'welcome',
  'monkey',
  'dragon',
  'master',
  'abc123',
  'passw0rd',
  'changeme',
  'default',
  'root',
  'toor',
  'test',
  'demo',
  'sample',
])

export function isWeakPassword(value: string | null | undefined): boolean {
  if (!value) return true
  if (value.length < 12) return true
  return weakBlocklist.has(value.toLowerCase())
}
