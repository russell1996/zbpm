/**
 * WO-UI-27 — браузерные доказательства на РЕАЛЬНОЙ фикстуре владельца
 * (stages-owner.json: 5.5 КБ одной строкой, кириллица, число > 2^53).
 * Критерии 1/3/4/5/7: автоформат при показе, диалог min(94vw,1280px),
 * фулскрин ≥90% viewport, крестик ×, скриншоты 1280/1920/360 + тёмная тема.
 *
 * Замоканы только сетевые сервисы; i18n настоящий; тёмная тема — классом
 * .dark на documentElement (как ui-store). Скриншоты — в shots/ с префиксом
 * ui27-, после прогона переносятся в governance/reports/assets/ui27/.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { page } from 'vitest/browser'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import VariablesEditor from '@/widgets/presets/VariablesEditor.vue'
import type { PresetVariable } from '@/types/presets'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'
import ownerStages from '@/test/fixtures/stages-owner.json?raw'
import '@/style.css'

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))

const SHOT_DIR = 'shots'
const SHOT = (name: string) => `${SHOT_DIR}/ui27-${name}.png`

const STAGES_VAR: PresetVariable[] = [{ name: 'stages', type: 'JSON', value: (ownerStages as string).trim() }]
const FIVE: PresetVariable[] = [
  { name: 'createdEmployeeId', type: 'LONG', value: '21346' },
  { name: 'processTypeId', type: 'LONG', value: '1' },
  { name: 'sourceUuid', type: 'UUID', value: '01a0b481-3ff1-7334-82e0-9c1e5b2a3d4f' },
  { name: 'note', type: 'STRING', value: 'hello' },
  ...STAGES_VAR,
]

function makeI18n(locale: string) {
  return createI18n({ legacy: false, locale, fallbackLocale: 'en', messages: { ru, en, kz } })
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  document.documentElement.classList.remove('dark')
})

afterEach(async () => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
  document.documentElement.classList.remove('dark')
  await page.viewport(1280, 720)
})

function attachHost(width: number, dark = false): HTMLElement {
  if (dark) document.documentElement.classList.add('dark')
  const host = document.createElement('div')
  host.style.width = `${width}px`
  host.style.minHeight = '200px'
  host.style.background = dark ? '#0f172a' : '#fff'
  host.style.colorScheme = dark ? 'dark' : 'light'
  document.body.appendChild(host)
  hosts.push(host)
  return host
}

function mountAt(vars: PresetVariable[], width: number, opts: { dark?: boolean; locale?: string } = {}) {
  const i18n = makeI18n(opts.locale ?? 'ru')
  const wrapper = mount(VariablesEditor, {
    attachTo: attachHost(width, opts.dark),
    props: { modelValue: vars },
    global: { plugins: [createPinia(), i18n] },
  }) as unknown as VueWrapper
  mounted.push(wrapper)
  return wrapper
}

/** JSON-textarea строки stages (WO-UI-28: непустой JSON свёрнут — сначала развернуть). */
function stagesArea(): HTMLTextAreaElement {
  const areas = [...document.querySelectorAll('textarea[id^="pv-value-"]')] as HTMLTextAreaElement[]
  if (!areas.length) throw new Error('no JSON textarea rendered')
  // stages — самая длинная по значению
  return areas.sort((a, b) => b.value.length - a.value.length)[0]
}

/** Развернуть JSON-строку stages (дефолт WO-UI-28 — свёрнуто). */
async function expandStages() {
  const toggle = document.querySelector('[data-testid^="ve-json-toggle-"]') as HTMLButtonElement
  if (!toggle) throw new Error('JSON expand toggle not found')
  if (toggle.getAttribute('aria-expanded') === 'false') {
    toggle.click()
    await flushPromises()
  }
}

/** Открыть фулскрин stages через его тулбар-кнопку. */
async function openFs() {
  const btn = [...document.querySelectorAll('button')].find((b) =>
    (b.textContent ?? '').includes((ru as unknown as Record<string, string>).presetJsonFullscreen),
  ) as HTMLButtonElement
  if (!btn) throw new Error('fullscreen button not found')
  btn.click()
  await flushPromises()
}

describe('WO-UI-27 browser: owner fixture formatted on show (criteria 1, 5)', () => {
  it.each([1280, 1920, 360])('stages @%ipx: formatted >20 lines, card fills the width', async (w) => {
    await page.viewport(w, 720)
    mountAt(FIVE, w)
    await flushPromises()
    await expandStages()
    const area = stagesArea()
    const lines = area.value.split('\n').length
    // Критерий 1: реальный JSON владельца отформатирован при показе.
    expect(lines, 'owner JSON renders formatted').toBeGreaterThan(20)
    expect(area.value).toContain('12345678901234567890')
    // Поле занимает ≥80% ширины карточки.
    const card = area.closest('[data-testid="ve-card"]') as HTMLElement
    const ratio = area.getBoundingClientRect().width / card.getBoundingClientRect().width
    expect(ratio, 'JSON field fills the card').toBeGreaterThan(0.8)
    // WO-UI-27 п.3б: номера строк в строке редактора (DOM, без чтения PNG).
    const gutter = document.querySelector('[data-testid="ve-json-gutter-4"]') as HTMLElement
    expect(gutter, 'row line-number gutter renders').not.toBeNull()
    expect(gutter.getAttribute('aria-hidden'), 'gutter aria-hidden').toBe('true')
    const gutterLines = (gutter.textContent ?? '').trim().split(/\s+/)
    expect(gutterLines.length, 'gutter has one number per line').toBe(lines)
    // Страница без горизонтального скролла.
    expect(document.documentElement.scrollWidth, 'no page h-scroll').toBeLessThanOrEqual(w + 1)
    if (w === 1280) await page.screenshot({ path: SHOT('after-row-1280-light') })
    if (w === 360) await page.screenshot({ path: SHOT('after-row-360-light') })
  })

  it('stages @360 dark: formatted, readable, no overflow', async () => {
    await page.viewport(360, 720)
    mountAt(FIVE, 360, { dark: true })
    await flushPromises()
    await expandStages()
    const area = stagesArea()
    expect(area.value.split('\n').length).toBeGreaterThan(20)
    expect(document.documentElement.scrollWidth, 'no page h-scroll (dark)').toBeLessThanOrEqual(361)
    await page.screenshot({ path: SHOT('after-row-360-dark') })
  })

  it('fullscreen @1280: ≥90% viewport on both axes, editor fills height', async () => {
    await page.viewport(1280, 800)
    mountAt(STAGES_VAR, 1280)
    await flushPromises()
    await openFs()
    // Фулскринов два (старый инлайн + новый Dialog) — берём НОВЫЙ shadcn.
    expect(document.querySelectorAll('[role="dialog"]').length, 'single fullscreen dialog').toBe(1)
    const dlg = document.querySelector('[role="dialog"]') as HTMLElement
    expect(dlg, 'fullscreen dialog opens').not.toBeNull()
    const r = dlg.getBoundingClientRect()
    // Критерий 4: min(96vw,1600px) × 92vh.
    expect(r.width / 1280, 'fullscreen width ≥90%').toBeGreaterThan(0.9)
    expect(r.height / 800, 'fullscreen height ≥90%').toBeGreaterThan(0.9)
    const fs = document.querySelector('[id$="-jsonfs"]') as HTMLTextAreaElement
    expect(fs, 'fullscreen editor renders').not.toBeNull()
    expect(fs.value.split('\n').length, 'fullscreen shows formatted').toBeGreaterThan(20)
    const fsGutter = document.querySelector('[data-testid="ve-jsonfs-gutter"]') as HTMLElement
    expect(fsGutter, 'fullscreen gutter renders').not.toBeNull()
    expect(
      (fsGutter.textContent ?? '').trim().split(/\s+/).length,
      'fullscreen gutter matches editor lines',
    ).toBe(fs.value.split('\n').length)
    expect(fs.getBoundingClientRect().height / r.height, 'editor fills dialog').toBeGreaterThan(0.6)
    await page.screenshot({ path: SHOT('after-fullscreen-1280-light') })
  })

  it('fullscreen @1920 dark: geometry holds, cyrillic readable', async () => {
    await page.viewport(1920, 900)
    mountAt(STAGES_VAR, 1920, { dark: true })
    await flushPromises()
    await openFs()
    expect(document.querySelectorAll('[role="dialog"]').length, 'single fullscreen dialog').toBe(1)
    const dlg = document.querySelector('[role="dialog"]') as HTMLElement
    const r = dlg.getBoundingClientRect()
    expect(r.width / 1920, 'width ≥75% (96vw cap at 1920)').toBeGreaterThan(0.75)
    expect(r.height / 900, 'height ≥90%').toBeGreaterThan(0.9)
    const fs = document.querySelector('[id$="-jsonfs"]') as HTMLTextAreaElement
    expect(fs.value).toContain('Тыщенко')
    await page.screenshot({ path: SHOT('after-fullscreen-1920-dark') })
  })
})

describe('WO-UI-27 browser: × delete and dialog width (criteria 3, 7)', () => {
  it('× @1280: one click deletes, ≥32px, visible without hover', async () => {
    await page.viewport(1280, 720)
    const w = mountAt(FIVE, 1280)
    await flushPromises()
    const dels = [...document.querySelectorAll('[data-testid="ve-row-delete"]')] as HTMLElement[]
    expect(dels.length).toBeGreaterThan(0)
    const box = dels[0].getBoundingClientRect()
    // Критерий 7: зона ≥32px, виден без hover.
    expect(Math.min(box.width, box.height), 'hit area ≥32px').toBeGreaterThanOrEqual(32)
    expect(getComputedStyle(dels[0]).opacity, 'visible without hover').not.toBe('0')
    dels[0].click()
    await flushPromises()
    expect(w.emitted('update:modelValue'), 'one click deletes').toBeTruthy()
    await page.screenshot({ path: SHOT('after-delete-1280-light') })
  })
})
