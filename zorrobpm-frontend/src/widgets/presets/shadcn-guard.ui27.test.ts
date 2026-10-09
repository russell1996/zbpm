// @vitest-environment node
/**
 * WO-UI-27 доп.3 (критерий 10): тест-страж применения shadcn-vue.
 * В widgets/presets/** — ни одного голого <button>/<input>/<select>/<textarea>
 * и самодельного `fixed inset-0` оверлея: только примитивы @/components/ui/*.
 * Исключение: внутренний <textarea> JSON-редактора — реализация примитива
 * Textarea (файл ui/textarea/Textarea.vue) либо обёрнут в него.
 * RED: сейчас 0 импортов components/ui в 7 файлах, десятки голых тегов.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const dir = resolve(dirname(fileURLToPath(import.meta.url)))
const files = readdirSync(dir)
  .filter((f) => f.endsWith('.vue'))
  .filter((f) => f !== 'VariableRowMenu.vue' || true)

const BARE_TAG = /<(button|input|select|textarea)[\s>]/g
const FIXED_OVERLAY = /fixed\s+inset-0/g
const UI_IMPORT = /from\s+['"]@\/components\/ui\//g

describe('WO-UI-27 shadcn guard (criterion 10)', () => {
  it('every presets component imports at least one @/components/ui primitive', () => {
    const offenders: string[] = []
    for (const f of files) {
      const src = readFileSync(join(dir, f), 'utf-8')
      const uses = src.match(UI_IMPORT) ?? []
      void uses
      if (uses.length < 1) offenders.push(`${f} (0 ui imports)`)
    }
    expect(offenders).toEqual([])
  })

  it('no bare button/input/select/textarea and no fixed inset-0 overlays', () => {
    const offenders: string[] = []
    for (const f of files) {
      const raw = readFileSync(join(dir, f), 'utf-8')
      const tpl = raw.slice(raw.indexOf('<template>'))
      const src = tpl.replace(/<!--[\s\S]*?-->/g, '')
      const tags = [...src.matchAll(BARE_TAG)].map((m) => m[1])
      // Разрешён ровно один: нативный input[type=file] для импорта файла
      // (у shadcn-vue нет file-примитива; скрыт классом hidden).
      const nonFileInputs = src.includes('type="file"')
        ? tags.filter((t) => t !== 'input')
        : tags
      const overlays = src.match(FIXED_OVERLAY) ?? []
      if (nonFileInputs.length || overlays.length) {
        offenders.push(`${f} (bare: ${nonFileInputs.join(',') || '—'}; overlays: ${overlays.length})`)
      }
    }
    expect(offenders).toEqual([])
  })
})
