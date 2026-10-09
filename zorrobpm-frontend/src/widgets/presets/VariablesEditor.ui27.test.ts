// @vitest-environment jsdom
/**
 * WO-UI-27 (доп.1–2, критерии 7–9): крестик × в один клик, без меню «⋯»,
 * без «Дублировать», без confirm; тихий undo-тост; автоформат JSON при показе.
 * RED на коде VT-3: меню/дубль/confirm есть, крестика нет, автоформата нет.
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

describe('WO-UI-27 delete as × (criteria 7–9)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('criterion 7: × removes the row in ONE click, no menu, no confirm', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'a', type: 'STRING', value: 'x' }] },
    })
    const del = w.find('[data-testid="ve-row-delete"]')
    expect(del.exists()).toBe(true)
    // Меню ⋯ и дублирования нет вообще.
    expect(w.find('[data-testid="ve-row-menu"]').exists()).toBe(false)
    expect(w.text()).not.toContain('presetDuplicateRow')
    await del.trigger('click')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1][0]).toEqual([])
    // Никакой красной плашки подтверждения.
    expect(w.find('[data-testid="ve-delete-confirm"]').exists()).toBe(false)
  })

  it('criterion 7: × is always visible, ≥32px hit area, keyboard reachable', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'a', type: 'STRING', value: 'x' }] },
    })
    const del = w.find('[data-testid="ve-row-delete"]')
    expect(del.exists()).toBe(true)
    // Не hover-only: нет классов, прячущих кнопку до наведения.
    const cls = del.attributes('class') ?? ''
    expect(cls).not.toMatch(/opacity-0|invisible|hidden|group-hover/)
    // Нативная кнопка (не div): фокус по Tab + Enter/Space из коробки.
    expect(del.element.tagName).toBe('BUTTON')
    expect(del.attributes('aria-label')).toBeTruthy()
  })

  it('criterion 8: undo toast restores the row with content at the same place', async () => {
    const rows: PresetVariable[] = [
      { name: 'keep', type: 'STRING', value: 'v' },
      { name: 'stages', type: 'JSON', value: ownerJson },
    ]
    const w = mount(VariablesEditor, { props: { modelValue: rows } })
    const dels = w.findAll('[data-testid="ve-row-delete"]')
    expect(dels).toHaveLength(2)
    await dels[0].trigger('click')
    // Тихий тост (не confirm): success с действием «Отменить», без красного.
    expect(mockToast.success).toHaveBeenCalledTimes(1)
    const [msg, opts] = mockToast.success.mock.calls[0] as [string, { action?: { label: string; onClick: () => void } }]
    expect(msg).not.toMatch(/!/ )
    expect(opts?.action?.label).toBeTruthy()
    // Отмена возвращает строку с содержимым на прежнее место.
    await w.setProps({ modelValue: [{ name: 'stages', type: 'JSON', value: ownerJson }] })
    opts?.action?.onClick()
    const emitted = w.emitted('update:modelValue')
    const last = emitted![emitted!.length - 1][0] as typeof rows
    expect(last).toHaveLength(2)
    expect(last[0]).toEqual(rows[0])
    expect(last[1].value).toBe(ownerJson)
  })

  it('criterion 8: deleting a fresh empty row shows no toast', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: '', type: 'STRING', value: '' }] },
    })
    await w.find('[data-testid="ve-row-delete"]').trigger('click')
    expect(w.emitted('update:modelValue')).toBeTruthy()
    expect(mockToast.success).not.toHaveBeenCalled()
  })
})

describe('WO-UI-27 autoformat on show (criterion 1)', () => {
  it('owner single-line JSON renders formatted (>20 lines) in the row editor', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'stages', type: 'JSON', value: ownerJson }] },
    })
    await flushPromises()
    const area = w.find('textarea[id^="pv-value-"]')
    expect(area.exists()).toBe(true)
    const shown = (area.element as HTMLTextAreaElement).value
    expect(shown.split('\n').length).toBeGreaterThan(20)
    // Точность: большое число не округлено показом.
    expect(shown).toContain('12345678901234567890')
  })

  it('emitted value stays semantically identical after autoformat', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'stages', type: 'JSON', value: ownerJson }] },
    })
    await flushPromises()
    const area = w.find('textarea[id^="pv-value-"]')
    // Пользователь ничего не трогал — эмит только после его правки;
    // показанное при этом парсится в тот же смысл (эквивалентность без потерь).
    expect(JSON.parse((area.element as HTMLTextAreaElement).value)).toEqual(JSON.parse(ownerJson))
  })

  it('BUG-1: typing in the JSON row editor keeps the typed text (no value loss)', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'stages', type: 'JSON', value: '{"a":1}' }] },
    })
    await flushPromises()
    const area = w.find('textarea[id^="pv-value-"]')
    await area.setValue('{"a":1,"b":2}')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted![emitted!.length - 1][0] as PresetVariable[]
    // shadcn-Textarea эмитит СТРОКУ; хендлер обязан хранить набранное,
    // а не `el.value` строки (= undefined) с TypeError в autoGrow.
    expect(last[0].value).toBe('{"a":1,"b":2}')
  })
})

describe('WO-UI-27 JSON line numbers (criterion 3b)', () => {
  it('row editor shows a gutter with one number per line (>20 on owner fixture)', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'stages', type: 'JSON', value: ownerJson }] },
    })
    await flushPromises()
    const gutter = w.find('[data-testid="ve-json-gutter-0"]')
    expect(gutter.exists()).toBe(true)
    expect(gutter.attributes('aria-hidden')).toBe('true')
    const lines = (w.find('textarea[id^="pv-value-"]').element as HTMLTextAreaElement).value.split('\n').length
    expect(lines).toBeGreaterThan(20)
    const numbers = (gutter.element.textContent ?? '').trim().split(/\s+/)
    expect(numbers).toHaveLength(lines)
    expect(numbers[0]).toBe('1')
    expect(numbers[numbers.length - 1]).toBe(String(lines))
  })
})
