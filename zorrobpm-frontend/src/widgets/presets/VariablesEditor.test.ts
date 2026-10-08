// @vitest-environment jsdom
/**
 * WO-VT-1 (фронт): VariablesEditor — редактор по типам, «спросить при
 * запуске», таблица/сырой JSON, allowEmptyString только для STRING.
 */
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import VariablesEditor from './VariablesEditor.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

describe('VariablesEditor', () => {
  it('renders one row per variable', () => {
    const w = mount(VariablesEditor, { props: { modelValue: [{ name: 'a', type: 'STRING', value: 'x' }] } })
    expect(w.findAll('input[id^="pv-name-"]')).toHaveLength(1)
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

  it('empty LONG with a name is marked as ask-at-launch', () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'n', type: 'LONG', value: '' }] },
    })
    expect(w.text()).toContain('presetAskAtLaunch')
  })

  it('allowEmptyString checkbox exists only for STRING and clears the ask marker', async () => {
    const wStr = mount(VariablesEditor, {
      props: { modelValue: [{ name: 's', type: 'STRING', value: '' }] },
    })
    expect(wStr.find('input[id^="pv-empty-"]').exists()).toBe(true)
    expect(wStr.text()).toContain('presetAskAtLaunch')
    await wStr.find('input[id^="pv-empty-"]').setValue(true)
    const emitted = wStr.emitted('update:modelValue')![0][0] as Array<{ allowEmptyString: boolean }>
    expect(emitted[0].allowEmptyString).toBe(true)

    const wLong = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'n', type: 'LONG', value: '' }] },
    })
    expect(wLong.find('input[id^="pv-empty-"]').exists()).toBe(false)
  })

  it('BOOLEAN renders a checkbox bound to true/false', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'b', type: 'BOOLEAN', value: 'false' }] },
    })
    const box = w.find('input[type="checkbox"]')
    await box.setValue(true)
    const emitted = w.emitted('update:modelValue')![0][0] as Array<{ value: string }>
    expect(emitted[0].value).toBe('true')
  })

  it('UUID generate button fills a uuid-shaped value', async () => {
    const w = mount(VariablesEditor, {
      props: { modelValue: [{ name: 'u', type: 'UUID', value: '' }] },
    })
    const btns = w.findAll('button')
    const gen = btns.find((b) => b.text() === 'presetGenerateUuid')!
    await gen.trigger('click')
    const emitted = w.emitted('update:modelValue')![0][0] as Array<{ value: string }>
    expect(emitted[0].value).toMatch(/^[0-9a-f-]{36}$/)
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
