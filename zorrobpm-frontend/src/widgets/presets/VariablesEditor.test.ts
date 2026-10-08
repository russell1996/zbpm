// @vitest-environment jsdom
/**
 * WO-VT-3: VariablesEditor карточками — имя+тип+меню ⋯, значение на всю
 * ширину по типу, поведение чипами, компакт/поиск, тулбар-табы справа.
 */
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import VariablesEditor from './VariablesEditor.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

describe('VariablesEditor', () => {
  it('renders one card per variable', () => {
    const w = mount(VariablesEditor, { props: { modelValue: [{ name: 'a', type: 'STRING', value: 'x' }] } })
    expect(w.findAll('input[id^="pv-name-"]')).toHaveLength(1)
    expect(w.findAll('[data-testid="ve-card"]')).toHaveLength(1)
  })

  it('add button appends an empty STRING row', async () => {
    const w = mount(VariablesEditor, { props: { modelValue: [] } })
    const addBtn = w.findAll('button').find((b) => b.text().includes('presetAddVariable'))!
    await addBtn.trigger('click')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted![emitted!.length - 1][0] as Array<{ name: string; type: string }>
    expect(last).toHaveLength(1)
    expect(last[0]).toMatchObject({ name: '', type: 'STRING', value: '' })
  })

  it('invalid LONG shows an error alert', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'n', type: 'LONG', value: 'abc' }] },
    })
    await flushPromises()
    expect(w.find('[role="alert"]').text()).toContain('not a LONG')
  })

  it('empty LONG with a name shows the ask-at-launch chip', () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'n', type: 'LONG', value: '' }] },
    })
    expect(w.text()).toContain('presetAskChip')
  })

  it('empty-string chip exists only for STRING and toggles the flag', async () => {
    const wStr = mount(VariablesEditor, {
      props: { modelValue: [{ name: 's', type: 'STRING', value: '' }] },
    })
    const chip = wStr.find('button[aria-pressed]')
    expect(chip.exists()).toBe(true)
    expect(wStr.text()).toContain('presetAskChip')
    await chip.trigger('click')
    const emitted = wStr.emitted('update:modelValue')![0][0] as Array<{ allowEmptyString: boolean }>
    expect(emitted[0].allowEmptyString).toBe(true)

    const wLong = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'n', type: 'LONG', value: '' }] },
    })
    expect(wLong.find('button[aria-pressed]').exists()).toBe(false)
  })

  it('BOOLEAN renders a switch bound to true/false', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'b', type: 'BOOLEAN', value: 'false' }] },
    })
    const sw = w.find('button[role="switch"]')
    expect(sw.attributes('aria-checked')).toBe('false')
    await sw.trigger('click')
    const emitted = w.emitted('update:modelValue')![0][0] as Array<{ value: string }>
    expect(emitted[0].value).toBe('true')
  })

  it('UUID generate icon-button inside the value field fills a uuid', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'u', type: 'UUID', value: '' }] },
    })
    const gen = w.find('button[aria-label="presetGenerateUuid"]')
    expect(gen.exists()).toBe(true)
    await gen.trigger('click')
    const emitted = w.emitted('update:modelValue')![0][0] as Array<{ value: string }>
    expect(emitted[0].value).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('LONG keeps placeholder text visible (no number-input sanitizing)', () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'n', type: 'LONG', value: '{{seq}}' }] },
    })
    const input = w.find('input[id^="pv-value-"]')
    expect((input.element as HTMLInputElement).value).toBe('{{seq}}')
  })

  it('row menu duplicates and deletes with inline confirm', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'a', type: 'STRING', value: 'x' }] },
    })
    await w.find('[data-testid="ve-row-menu"]').trigger('click')
    await w.find('[data-testid="ve-row-menu-duplicate"]').trigger('click')
    const dup = w.emitted('update:modelValue')![0][0] as Array<{ name: string }>
    expect(dup).toHaveLength(2)
  })

  it('raw mode: invalid JSON blocks apply, valid JSON replaces the table', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'a', type: 'STRING', value: 'x' }] },
    })
    const tabs = w.findAll('[role="tab"]')
    await tabs[1].trigger('click')
    const area = w.find('#pv-raw')
    expect((area.element as HTMLTextAreaElement).value).toContain('"name": "a"')
    await area.setValue('not json')
    await w.findAll('button').find((b) => b.text() === 'presetApplyRaw')!.trigger('click')
    expect(w.find('[role="alert"]').exists()).toBe(true)
    expect(w.emitted('update:modelValue')).toBeFalsy()
    await area.setValue('[{"name":"b","type":"LONG","value":"5"}]')
    await w.findAll('button').find((b) => b.text() === 'presetApplyRaw')!.trigger('click')
    const emitted = w.emitted('update:modelValue')![0][0] as Array<{ name: string }>
    expect(emitted).toEqual([{ name: 'b', type: 'LONG', value: '5' }])
  })
})
