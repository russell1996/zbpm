// @vitest-environment node
/**
 * WO-ACL-11 criterion 16: no menu item in ANY locale is truncated with an
 * ellipsis in the expanded rail. The rail is w-64 (256px): px-4 (32px) +
 * gap-3 (12px) + 16px icon leave ~196px for the label; text-sm (14px) Cyrillic
 * averages ≈8px/char, so 24 chars fits with margin and anything longer would
 * hit `truncate` (whitespace-nowrap + ellipsis, SidebarNav.vue).
 *
 * This test uses the REAL locale files (import.meta.glob ?raw + JSON.parse),
 * so a long label added to any of the three locales fails the build.
 */
import { describe, it, expect } from 'vitest'

// 20 chars ≈ 160-170px of text-sm Cyrillic — safely inside the ~196px label
// area of the expanded rail (256px − px-4×2 − gap-3 − icon). Longer labels hit
// `truncate` on wide glyphs ("Пайдаланушы тапсырмалары", 24 chars, did).
const MAX_LABEL_CHARS = 20

const NAV_LABEL_KEYS = [
  'dashboard', 'definitions', 'instances', 'tasks', 'serviceTasks', 'incidents',
  'timers', 'messages', 'dmn', 'analytics', 'users', 'submissionQueue',
]

describe('WO-ACL-11 criterion 16: expanded rail menu labels never truncate', () => {
  it('fits in the expanded rail in every locale (real strings)', () => {
    const locales = import.meta.glob('../locales/*.json', { import: 'default', eager: true }) as Record<string, Record<string, string>>
    const failures: string[] = []
    for (const [path, messages] of Object.entries(locales)) {
      const locale = path.replace('../locales/', '').replace('.json', '')
      for (const key of NAV_LABEL_KEYS) {
        const label = messages[key]
        if (label === undefined) {
          failures.push(`${locale}:${key} is MISSING`)
          continue
        }
        if (label.length > MAX_LABEL_CHARS) {
          failures.push(`${locale}:${key} = "${label}" (${label.length} chars > ${MAX_LABEL_CHARS})`)
        }
      }
    }
    expect(failures, `Menu labels that would truncate in the expanded rail:\n${failures.join('\n')}`).toHaveLength(0)
  })
})