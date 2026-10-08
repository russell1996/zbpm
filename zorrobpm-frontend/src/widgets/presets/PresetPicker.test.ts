// @vitest-environment jsdom
/**
 * WO-VT-1 (фронт): PresetPicker — «Ручной ввод | Из шаблона», предпросмотр с
 * правкой копии, блокировка по «спросить», скрытие при выключенном флаге.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PresetPicker from './PresetPicker.vue'

const mockList = vi.hoisted(() => vi.fn())
const mockGet = vi.hoisted(() => vi.fn())
vi.mock('@/services/presetService', () => ({
  listPresets: mockList,
  getPreset: mockGet,
  isPresetsDisabled: (e: unknown) => (e as { disabled?: boolean })?.disabled === true,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string, p?: unknown) => k }),
}))

const PRESET = {
  id: 'p1',
  processDefinitionKey: 'k',
  targetKind: 'START',
  targetRef: null,
  name: 'base',
  description: null,
  variables: [
    { name: 'n', type: 'LONG', value: '7' },
    { name: 'ask', type: 'UUID', value: '' },
  ],
  ownerUserId: 'u1',
  visibility: 'PRIVATE',
  favorite: false,
  createdAt: '2026-01-01',
  updatedAt: '2026-01-01',
  version: 3,
}

function render() {
  return mount(PresetPicker, {
    props: { processKey: 'k', targetKind: 'START' },
  })
}

describe('PresetPicker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockList.mockResolvedValue([{ ...PRESET }])
    mockGet.mockResolvedValue({ ...PRESET })
  })

  it('manual mode renders the editor and emits change', async () => {
    const w = render()
    const editor = w.findComponent({ name: undefined })
    expect(w.text()).toContain('presetManualMode')
    await w.find('input[id^="pv-name-"]')
    // type a variable name through the editor
    const nameInput = w.find('input[id^="pv-name-0"]')
    if (!nameInput.exists()) {
      // no rows yet — add one first
      const addBtn = w.findAll('button').find((b) => b.text().includes('presetAddVariable'))!
      await addBtn.trigger('click')
    }
    await w.find('input[id^="pv-name-0"]').setValue('m')
    expect(w.emitted('change')).toBeTruthy()
    expect(editor).toBeTruthy()
  })

  it('template mode lists presets and loads the chosen one for editing', async () => {
    const w = render()
    const tabs = w.findAll('[role="tab"]')
    await tabs[1].trigger('click')
    await flushPromises()
    expect(mockList).toHaveBeenCalledWith({ key: 'k', kind: 'START' })
    expect(w.text()).toContain('base')
    await w.find('#preset-select').setValue('p1')
    await flushPromises()
    expect(mockGet).toHaveBeenCalledWith('p1')
    // копия переменных — в редакторе (видны оба имени)
    expect(w.html()).toContain('value="n"')
    expect(w.html()).toContain('value="ask"')
  })

  it('ask-fields are reported and block apply', async () => {
    const w = render()
    const tabs = w.findAll('[role="tab"]')
    await tabs[1].trigger('click')
    await flushPromises()
    await w.find('#preset-select').setValue('p1')
    await flushPromises()
    const vm = w.vm as unknown as { missingAsk: string[]; canApply: boolean }
    expect(vm.missingAsk).toEqual(['ask'])
    expect(vm.canApply).toBe(false)
  })

  it('getVariables expands placeholders of filled rows', async () => {
    const w = render()
    const tabs = w.findAll('[role="tab"]')
    await tabs[1].trigger('click')
    await flushPromises()
    await w.find('#preset-select').setValue('p1')
    await flushPromises()
    // заполняем ask-поле руками в копии
    const askInput = w.find('input[id="pv-value-1"]')
    await askInput.setValue('123e4567-e89b-12d3-a456-426614174000')
    const vm = w.vm as unknown as {
      missingAsk: string[]
      getVariables: () => Array<{ name: string; value: string }>
    }
    expect(vm.missingAsk).toEqual([])
    const vars = vm.getVariables()
    expect(vars.find((x) => x.name === 'n')).toMatchObject({ value: '7' })
    expect(vars.find((x) => x.name === 'ask')?.value).toBe('123e4567-e89b-12d3-a456-426614174000')
  })

  it('disabled flag hides the picker', async () => {
    mockList.mockRejectedValue({ disabled: true })
    const w = render()
    const tabs = w.findAll('[role="tab"]')
    await tabs[1].trigger('click')
    await flushPromises()
    expect(w.text()).toBe('')
  })
})
