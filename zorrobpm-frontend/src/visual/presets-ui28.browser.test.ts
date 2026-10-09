/**
 * WO-UI-28 — браузерные МЕТРИКИ на реальной фикстуре владельца
 * (stages-owner.json: 5 КБ одной строкой). НЕ читать PNG через Read.
 * Критерии 1/3/6: свёрнуто ≤2 строк без скроллов; развёрнуто ≤14 строк
 * с внутренним скроллом; гаттер не выпирает; дефолты; значение не мутирует.
 *
 * Замоканы только сетевые сервисы; i18n настоящий (ru).
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

const STAGES: PresetVariable[] = [{ name: 'stages', type: 'JSON', value: (ownerStages as string).trim() }]

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
  await page.viewport(1280, 720)
})

function mountAt(vars: PresetVariable[], width: number, locale = 'ru') {
  const i18n = makeI18n(locale)
  const host = document.createElement('div')
  host.style.width = `${width}px`
  host.style.minHeight = '200px'
  host.style.background = '#fff'
  document.body.appendChild(host)
  hosts.push(host)
  const wrapper = mount(VariablesEditor, {
    attachTo: host,
    props: { modelValue: vars },
    global: { plugins: [createPinia(), i18n] },
  }) as unknown as VueWrapper
  mounted.push(wrapper)
  return wrapper
}

function q<T extends Element = HTMLElement>(sel: string): T {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return el as T
}

/** Реальное число строк отформатированного stages (счётчик обязан совпасть). */
function formattedStagesLines(): number {
  const area = q<HTMLTextAreaElement>('textarea[id^="pv-value-"]')
  return area.value.split('\n').length
}

async function expand() {
  const toggle = q<HTMLButtonElement>('[data-testid="ve-json-toggle-0"]')
  if (toggle.getAttribute('aria-expanded') === 'false') {
    toggle.click()
    await flushPromises()
  }
}

describe('WO-UI-28 browser criterion 1: collapsed by default, ≤2 lines, no scroll', () => {
  it.each([1280, 1920, 360])('stages @%ipx collapsed: preview ≤64px, no h-scroll', async (w) => {
    await page.viewport(w, 720)
    mountAt(STAGES, w)
    await flushPromises()
    const preview = q('[data-testid="ve-json-preview-0"]')
    const h = preview.getBoundingClientRect().height
    // Компакт-превью в 1 строку: ≤ 2 строк высоты (~64px), без пустого места.
    expect(h, 'collapsed preview height').toBeLessThanOrEqual(64)
    // Без горизонтального скролла — ни на странице, ни внутри превью.
    expect(document.documentElement.scrollWidth, 'no page h-scroll').toBeLessThanOrEqual(w + 1)
    expect(preview.scrollWidth, 'no inner h-scroll in preview').toBeLessThanOrEqual(
      preview.clientWidth + 1,
    )
    // Счётчик — реальное число строк ОТФОРМАТИРОВАННОГО значения.
    const counter = [...document.querySelectorAll('span')].find((s) =>
      /строк: \d+/.test(s.textContent ?? ''),
    )
    expect(counter, 'formatted line counter visible when collapsed').toBeTruthy()
    await expand()
    const n = formattedStagesLines()
    expect(n, 'stages formats to many lines').toBeGreaterThan(20)
    expect(counter!.textContent, 'counter shows formatted line count').toContain(`строк: ${n}`)
  })
})

describe('WO-UI-28 browser criterion 2: toggle label is the action (ru)', () => {
  it('collapsed → «Развернуть», expanded → «Свернуть», aria-expanded flips', async () => {
    await page.viewport(1280, 720)
    mountAt(STAGES, 1280)
    await flushPromises()
    const toggle = () => q<HTMLButtonElement>('[data-testid="ve-json-toggle-0"]')
    expect(toggle().textContent).toContain('Развернуть')
    expect(toggle().getAttribute('aria-expanded')).toBe('false')
    toggle().click()
    await flushPromises()
    expect(toggle().textContent).toContain('Свернуть')
    expect(toggle().getAttribute('aria-expanded')).toBe('true')
    toggle().click()
    await flushPromises()
    expect(toggle().textContent).toContain('Развернуть')
    expect(toggle().getAttribute('aria-expanded')).toBe('false')
  })
})

describe('WO-UI-28 browser criterion 3: expanded ≤14 lines, inner scroll, gutter clipped', () => {
  it.each([1280, 1920, 360])('stages @%ipx expanded: capped height, inner scroll, gutter fits', async (w) => {
    await page.viewport(w, 720)
    mountAt(STAGES, w)
    await flushPromises()
    await expand()
    const area = q<HTMLTextAreaElement>('textarea[id^="pv-value-"]')
    const gutter = q('[data-testid="ve-json-gutter-0"]')
    const lh = parseFloat(getComputedStyle(area).lineHeight || '0') || 19.6
    // Потолок 14 строк + padding: поле не «огромное».
    expect(area.clientHeight, 'expanded editor ≤ 14 lines').toBeLessThanOrEqual(14 * lh + 12)
    // Дальше — ВНУТРЕННИЙ скролл (контента больше, чем видно).
    expect(area.scrollHeight, 'inner scroll active').toBeGreaterThan(area.clientHeight)
    // Гаттер обрезан по высоте текстового поля: никаких номеров за пределами.
    const ah = area.getBoundingClientRect().height
    const gh = gutter.getBoundingClientRect().height
    expect(Math.abs(gh - ah), 'gutter height == textarea height').toBeLessThanOrEqual(6)
    expect(gutter.getBoundingClientRect().bottom, 'gutter does not protrude').toBeLessThanOrEqual(
      area.getBoundingClientRect().bottom + 6,
    )
    // Нет пустой области: номеров столько же, сколько строк значения.
    const numbers = (gutter.textContent ?? '').trim().split(/\s+/).length
    expect(numbers, 'one gutter number per line').toBe(area.value.split('\n').length)
  })
})

describe('WO-UI-28 browser criterion 4: empty value opens editor at once', () => {
  it('empty JSON renders textarea immediately, no preview', async () => {
    await page.viewport(1280, 720)
    mountAt([{ name: 'j', type: 'JSON', value: '' }], 1280)
    await flushPromises()
    expect(document.querySelector('[data-testid="ve-json-preview-0"]')).toBeNull()
    expect(document.querySelector('textarea[id^="pv-value-"]')).not.toBeNull()
  })
})

describe('WO-UI-28 browser criterion 5: value survives collapse cycles', () => {
  it('expand→collapse→expand keeps the formatted text byte-identical', async () => {
    await page.viewport(1280, 720)
    mountAt(STAGES, 1280)
    await flushPromises()
    await expand()
    const before = q<HTMLTextAreaElement>('textarea[id^="pv-value-"]').value
    const toggle = q<HTMLButtonElement>('[data-testid="ve-json-toggle-0"]')
    toggle.click()
    await flushPromises()
    toggle.click()
    await flushPromises()
    const after = q<HTMLTextAreaElement>('textarea[id^="pv-value-"]').value
    expect(after).toBe(before)
    expect(after.split('\n').length).toBeGreaterThan(20)
  })
})
