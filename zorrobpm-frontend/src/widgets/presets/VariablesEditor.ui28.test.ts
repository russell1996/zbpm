// @vitest-environment jsdom
/**
 * WO-UI-28 (критерии 1–5): JSON-строка компактная по умолчанию.
 * RED на коде master: тоггла ve-json-toggle-* нет вообще (был toggleJsonLines
 * со статичной подписью), непустой JSON сразу развёрнут в textarea 8 строк /
 * 40vh, ключей presetJsonExpand/presetJsonCollapse нет в локалях.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import VariablesEditor from './VariablesEditor.vue'
import type { PresetVariable } from '@/types/presets'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

const ownerJson: string = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../test/fixtures/stages-owner.json'),
  'utf-8',
).trim()

function locales(): Record<string, Record<string, string>> {
  const dir = resolve(dirname(fileURLToPath(import.meta.url)), '../../locales')
  const out: Record<string, Record<string, string>> = {}
  for (const l of ['en', 'ru', 'kz']) {
    out[l] = JSON.parse(readFileSync(resolve(dir, `${l}.json`), 'utf-8'))
  }
  return out
}

describe('WO-UI-28 criterion 1: non-empty JSON collapsed by default', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('non-empty JSON renders preview, no textarea, until expanded', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'stages', type: 'JSON', value: ownerJson }] },
    })
    await flushPromises()
    expect(w.find('[data-testid="ve-json-preview-0"]').exists()).toBe(true)
    expect(w.find('textarea[id^="pv-value-"]').exists()).toBe(false)
    // Превью — одна строка с обрезкой, без горизонтального скролла.
    const preview = w.find('[data-testid="ve-json-preview-0"]')
    expect((preview.element.textContent ?? '').includes('\n')).toBe(false)
    expect(preview.classes()).toContain('overflow-hidden')
  })

  it('each JSON row collapses independently; two rows = two previews', async () => {
    const w = mount(VariablesEditor, {
      props: {
        modelValue: [
          { name: 'a', type: 'JSON', value: '{"a":1}' },
          { name: 'b', type: 'JSON', value: '{"b":2}' },
        ],
      },
    })
    await flushPromises()
    expect(w.find('[data-testid="ve-json-preview-0"]').exists()).toBe(true)
    expect(w.find('[data-testid="ve-json-preview-1"]').exists()).toBe(true)
    await w.find('[data-testid="ve-json-toggle-0"]').trigger('click')
    await flushPromises()
    // Первая развернулась, вторая осталась свёрнутой.
    expect(w.find('[data-testid="ve-json-preview-0"]').exists()).toBe(false)
    expect(w.find('[data-testid="ve-json-preview-1"]').exists()).toBe(true)
  })
})

describe('WO-UI-28 criterion 2: toggle shows the ACTION, aria-expanded', () => {
  it('collapsed → presetJsonExpand + aria-expanded=false; expanded → presetJsonCollapse + true', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'j', type: 'JSON', value: '{"a":1}' }] },
    })
    await flushPromises()
    const toggle = w.find('[data-testid="ve-json-toggle-0"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toContain('presetJsonExpand')
    expect(toggle.text()).not.toContain('presetJsonCollapse')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.attributes('aria-controls')).toBeTruthy()

    await toggle.trigger('click')
    await flushPromises()
    const toggle2 = w.find('[data-testid="ve-json-toggle-0"]')
    expect(toggle2.text()).toContain('presetJsonCollapse')
    expect(toggle2.text()).not.toContain('presetJsonExpand')
    expect(toggle2.attributes('aria-expanded')).toBe('true')

    await toggle2.trigger('click')
    await flushPromises()
    const toggle3 = w.find('[data-testid="ve-json-toggle-0"]')
    expect(toggle3.text()).toContain('presetJsonExpand')
    expect(toggle3.attributes('aria-expanded')).toBe('false')
  })

  it('i18n: presetJsonExpand/presetJsonCollapse in en+ru+kz, presetToggleJsonLines gone', () => {
    const loc = locales()
    for (const l of ['en', 'ru', 'kz']) {
      expect(loc[l].presetJsonExpand, `${l}.presetJsonExpand`).toBeTruthy()
      expect(loc[l].presetJsonCollapse, `${l}.presetJsonCollapse`).toBeTruthy()
      expect(loc[l].presetToggleJsonLines, `${l}.presetToggleJsonLines removed`).toBeUndefined()
    }
    // Ключи — действия, не «через слеш».
    expect(loc.ru.presetJsonExpand).not.toContain('/')
    expect(loc.ru.presetJsonCollapse).not.toContain('/')
    expect(loc.en.presetJsonExpand).not.toContain('/')
  })

  it('mелочи WO-UI-28 п.6: мёртвый ключ dialogClose удалён из всех локалей', () => {
    const loc = locales()
    for (const l of ['en', 'ru', 'kz']) {
      expect(loc[l].dialogClose, `${l}.dialogClose removed`).toBeUndefined()
    }
  })
})

describe('WO-UI-28 criterion 3: expanded editor capped at 14 lines, gutter clipped', () => {
  it('textarea min 3 lines / max 14 lines (not 8 / 40vh), container clips gutter', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'stages', type: 'JSON', value: ownerJson }] },
    })
    await flushPromises()
    await w.find('[data-testid="ve-json-toggle-0"]').trigger('click')
    await flushPromises()
    const area = w.find('textarea[id^="pv-value-"]')
    expect(area.exists()).toBe(true)
    const style = area.attributes('style') ?? ''
    // Vue нормализует calc (3*1.4→4.2, 14*1.4→19.6): сверяем вычисленную форму.
    expect(style).toContain('min-height: calc(4.2em + 8px)')
    expect(style).toContain('max-height: calc(19.6em + 8px)')
    expect(style).not.toContain('40vh')
    expect(style).not.toContain('11.2em')
    // Контейнер режет гаттер по высоте поля.
    const body = w.find('[id$="-jsonbody-0"]')
    expect(body.classes()).toContain('overflow-hidden')
  })

  it('format button hidden when collapsed, shown when expanded with minified input', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'j', type: 'JSON', value: '{"a":1}' }] },
    })
    await flushPromises()
    expect(w.text()).not.toContain('presetFormatJson')
    await w.find('[data-testid="ve-json-toggle-0"]').trigger('click')
    await flushPromises()
    // Однострочный валидный ввод — форматировать есть что.
    expect(w.text()).toContain('presetFormatJson')
  })

  it('format button hidden for already-multiline input even when expanded', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'j', type: 'JSON', value: '{\n  "a": 1\n}' }] },
    })
    await flushPromises()
    await w.find('[data-testid="ve-json-toggle-0"]').trigger('click')
    await flushPromises()
    expect(w.text()).not.toContain('presetFormatJson')
  })
})

describe('WO-UI-28 criterion 4: empty value opens editor; validation error expands', () => {
  it('empty JSON value renders the editor immediately, no preview', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'j', type: 'JSON', value: '' }] },
    })
    await flushPromises()
    expect(w.find('[data-testid="ve-json-preview-0"]').exists()).toBe(false)
    expect(w.find('textarea[id^="pv-value-"]').exists()).toBe(true)
  })

  it('invalid JSON row is expanded (preview hidden) so the error is visible', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'j', type: 'JSON', value: '{"a": truely}' }] },
    })
    await flushPromises()
    expect(w.find('[data-testid="ve-json-preview-0"]').exists()).toBe(false)
    expect(w.find('textarea[id^="pv-value-"]').exists()).toBe(true)
    // О-1 verifier r1: тоггл при ошибке — disabled с причиной (иначе мёртвый клик).
    const toggle = w.find('[data-testid="ve-json-toggle-0"]')
    expect(toggle.attributes('disabled')).toBeDefined()
  })
})

describe('WO-UI-28 criterion 5: collapse does not mutate the value', () => {
  it('expand→collapse→expand cycle emits nothing and keeps raw intact', async () => {
    const raw = '{"a":1,"b":[1,2,3]}'
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'j', type: 'JSON', value: raw }] },
    })
    await flushPromises()
    const toggle = () => w.find('[data-testid="ve-json-toggle-0"]')
    await toggle().trigger('click')
    await flushPromises()
    await toggle().trigger('click')
    await flushPromises()
    await toggle().trigger('click')
    await flushPromises()
    // Ни одного update:modelValue от самого сворачивания.
    expect(w.emitted('update:modelValue') ?? []).toHaveLength(0)
    // Развёрнутый показ — тот же raw, отформатированный без потерь.
    const area = w.find('textarea[id^="pv-value-"]')
    expect(JSON.parse((area.element as HTMLTextAreaElement).value)).toEqual(JSON.parse(raw))
  })
})
