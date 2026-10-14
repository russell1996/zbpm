// @vitest-environment node
/**
 * WO-UI-35 (CONTRIBUTING.md, G24): guard — в TruncatedText.vue нет «голых»
 * UI-контролей (<button>/<input>/<textarea>/<select>) и самодельных
 * `fixed inset-0`-оверлеев: только примитивы @/components/ui/*
 * (shadcn-vue Dialog + Button). Дублирует построчную проверку гейта
 * ci/wo-report-gate.sh G24 (он смотрит только ДОБАВЛЕННЫЕ строки диффа —
 * на чистом checkout их не видно), прибивая контракт на диске.
 *
 * Мутация: добавить голый <button> в шаблон → тест красный.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))

const BARE = /<button|<input|<textarea|<select|fixed inset-0/

/**
 * Убирает из Vue-источника ВСЕ комментарии, в которых может упоминаться
 * голый тег как текст (не как разметка): HTML-комментарии в шаблоне
 * (скобки-дефисы, в т.ч. многострочные) и JS-комментарии (блок и
 * однострочные) — иначе guard красится на собственной документации
 * (P-64: проверка не должна ломаться о свой же текст).
 */
function stripComments(src: string): string[] {
  let out = src
  // JS блок-комментарии (script-секция): /* … */ не построчно-разрываемые
  // строки не тронем — ниже идём ПО СТРОКАМ, поэтому снимаем и их.
  out = out.replace(/\/\*[\s\S]*?\*\//g, '')
  // HTML-комментарии (шаблон): <!-- … -->
  out = out.replace(/<!--[\s\S]*?-->/g, '')
  // JS однострочные: // … (вне строк в .vue-скрипте такого паттерна нет)
  return out.split('\n').map((l) => {
    const i = l.indexOf('//')
    return i >= 0 && !l.slice(0, i).includes("'") && !l.slice(0, i).includes('"')
      ? l.slice(0, i)
      : l
  })
}

function bareTagLines(file: string): string[] {
  const src = readFileSync(join(here, file), 'utf-8')
  return stripComments(src).filter((line) => BARE.test(line.trim()))
}

describe('WO-UI-35 G24 guard: TruncatedText on shadcn-vue only', () => {
  it('TruncatedText.vue — ноль голых <button>/<input>/<textarea>/<select> и оверлеев', () => {
    expect(bareTagLines('./TruncatedText.vue')).toEqual([])
  })

  it('TruncatedText.vue использует shadcn-vue Dialog и Button', () => {
    const src = readFileSync(join(here, 'TruncatedText.vue'), 'utf-8')
    expect(src).toMatch(/from ['"]@\/components\/ui\/dialog['"]/)
    expect(src).toMatch(/from ['"]@\/components\/ui\/button['"]/)
  })
})
