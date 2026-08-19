// @vitest-environment node
/**
 * WO-ACL-11 criterion 11: user-visible text in <template> that does NOT pass
 * through t() fails the build. The ACL-10 scanner only caught missing keys;
 * literal text (text nodes + title/placeholder/aria-label) was invisible to it
 * — and survived on the stand (P-53-class: "Click an element…", "Bind and
 * edit…", "(current)").
 *
 * Rules:
 *  - text nodes between tags (not inside {{ }}), and static attribute values
 *    of title/placeholder/aria-label, must not contain "words" (letters);
 *  - the whitelist holds ONLY technical tokens (DMN, BPMN, FEEL, id, units…);
 *    every entry is visible in this diff;
 *  - a node that contains an interpolation is skipped entirely (it is already
 *    bound to a t() call or data — same as the ACL-10 scanner's behavior).
 */
import { describe, it, expect } from 'vitest'

const TEMPLATE_RE = /<template>([\s\S]*?)<\/template>/g
const TEXT_NODE_RE = />([^<{]+)</g
const ATTR_RE = /(?<!:)(?:title|placeholder|aria-label)=["']([^"']+)["']/g
const INTERP_RE = /\{\{([^{}]*?)\}\}/g
const WORD_RE = /[A-Za-zА-Яа-яЁё]{2,}/g

/** Technical tokens that are allowed to appear outside t() (kept short on purpose). */
const WHITELIST = new Set([
  'DMN', 'BPMN', 'FEEL', 'XML', 'ID', 'UUID', 'JSON', 'STRING', 'LONG',
  'DOUBLE', 'BOOLEAN', 'URL', 'API', 'CSV', 'Key', 'key', 'ID', 'id',
  'ZBPM', 'BPM', 'Schema',
  // JS/domain tokens that only look like copy inside interpolations:
  'FULL', 'object', 'embedded', 'resolve', 'bpmn', 'tasks', 'serviceTasks',
  'variables', 'incidents', 'history',
])

interface Hit { file: string; text: string; where: string }

/**
 * The single root <template> of a .vue file. NOTE: <template> also appears with
 * attributes (<template v-if=…>), so the closing tag cannot be found with a
 * lazy regex — the FIRST </template> may close a nested v-if template. The root
 * template is the one that starts with a bare <template> and closes LAST.
 */
function rootTemplate(raw: string): string {
  const start = raw.indexOf('<template>')
  const end = raw.lastIndexOf('</template>')
  if (start < 0 || end <= start) return ''
  return raw.slice(start, end)
}

function findUntranslated(raw: string, file: string): Hit[] {
  const hits: Hit[] = []
  const template = rootTemplate(raw).replace(/<!--[\s\S]*?-->/g, '')

  for (const t of template.matchAll(TEXT_NODE_RE)) {
    const text = t[1].trim()
    if (!text) continue
    if (text.includes('{{')) continue // bound to t()/data — out of scope
    // attribute/expression fragments ("…" or '…' or = inside) are not text nodes
    if (/["'=]/.test(text)) continue
    // icon glyphs rendered as HTML entities (e.g. &#x26A0;&#xFE0F;) are not copy
    if (/^&#x[0-9A-Fa-f]+;/.test(text) && !WORD_RE.test(text.replace(/&#x[0-9A-Fa-f]+;/g, ''))) continue
    const words = text.match(WORD_RE)
    if (!words) continue
    for (const w of words) {
      if (!WHITELIST.has(w)) {
        hits.push({ file, text: text.slice(0, 80), where: 'text node' })
        break
      }
    }
  }

  for (const a of template.matchAll(ATTR_RE)) {
    const value = a[1].trim()
    if (!value) continue
    if (value.includes('{{')) continue
    const words = value.match(WORD_RE)
    if (!words) continue
    for (const w of words) {
      if (!WHITELIST.has(w)) {
        hits.push({ file, text: value.slice(0, 80), where: 'attribute' })
        break
      }
    }
  }

  // string literals inside {{ … }} that are NOT t('…') arguments — user-visible
  // copy bound to a condition (e.g. `{{ x ? 'Sequence flow' : t('element') }}`)
  for (const i of template.matchAll(INTERP_RE)) {
    const expr = i[1]
    for (const lit of expr.matchAll(/'([^']*)'/g)) {
      const before = expr.slice(0, lit.index)
      if (/t\(\s*$/.test(before)) continue // argument of t() — translated
      const words = lit[1].match(WORD_RE)
      if (!words) continue
      for (const w of words) {
        if (!WHITELIST.has(w)) {
          hits.push({ file, text: lit[1].slice(0, 80), where: 'interpolation literal' })
          break
        }
      }
    }
  }
  return hits
}

describe('WO-ACL-11 criterion 11: no user-visible literal text in templates', () => {
  it('scans every .vue file and finds no untranslated text', () => {
    const files = import.meta.glob('../**/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>
    const allHits: Hit[] = []
    for (const [path, raw] of Object.entries(files)) {
      allHits.push(...findUntranslated(raw as string, path.replace('../', '')))
    }
    const byFile = new Map<string, string[]>()
    for (const h of allHits) {
      const list = byFile.get(h.file) ?? []
      list.push(`${h.where}: "${h.text}"`)
      byFile.set(h.file, list)
    }
    const detail = [...byFile.entries()]
      .map(([f, list]) => `  ${f}\n${list.map((l) => `    - ${l}`).join('\n')}`)
      .join('\n')
    expect(allHits, `Untranslated user-visible text found:\n${detail}`).toHaveLength(0)
  })
})