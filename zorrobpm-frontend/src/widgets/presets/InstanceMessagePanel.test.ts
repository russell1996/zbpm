// @vitest-environment jsdom
/**
 * WO-VT-1 раунд 2 (Б-3): InstanceMessagePanel блокирует publish при
 * невалидных строках (pickerInvalid) — как 5 соседних страниц.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import InstanceMessagePanel from './InstanceMessagePanel.vue'
import PresetPicker from './PresetPicker.vue'

const mockPublish = vi.hoisted(() => vi.fn())
vi.mock('@/services/messagePublishService', () => ({
  publishMessage: mockPublish,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))
vi.mock('@/services/presetService', () => ({
  listPresets: vi.fn().mockResolvedValue([]),
  getPreset: vi.fn(),
  isPresetsDisabled: () => false,
}))

function render() {
  return mount(InstanceMessagePanel, {
    props: { processKey: 'order', processInstanceId: 'pi1' },
  })
}

describe('InstanceMessagePanel — invalid guard (WO-VT-1 Б-3)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockPublish.mockResolvedValue({})
  })

  it('invalid JSON blocks publish (button disabled, service never called)', async () => {
    const w = render()
    await w.find('#msg-name').setValue('orderPaid')
    await flushPromises()
    const picker = w.findComponent(PresetPicker)
    expect(picker.exists()).toBe(true)
    await picker.findAll('button').find((b) => b.text().includes('presetAddVariable'))!.trigger('click')
    await picker.find('input[id^="pv-name-"]').setValue('cfg')
    await picker.find('select[id^="pv-type-"]').setValue('JSON')
    await flushPromises()
    // NOTE: LONG/DOUBLE rows render <input type="number"> — DOM-санитизация
    // превращает любой нечисловой ввод в '' (ask-путь), поэтому невалидный
    // (не ask) ввод проверяется через JSON-textarea: '{invalid}' — это
    // pickerInvalid=true при пустом askMissing.
    await picker.find('textarea[id^="pv-value-"]').setValue('{invalid}')
    await flushPromises()
    const pvm = picker.vm as unknown as { missingAsk: string[]; hasErrors: boolean }
    expect(pvm.missingAsk).toEqual([])
    expect(pvm.hasErrors).toBe(true)
    const btns = w.findAll('button')
    const publishBtn = btns.find((b) => b.text().trim() === 'presetPublish')!
    expect((publishBtn.element as HTMLButtonElement).disabled).toBe(true)
    await publishBtn.trigger('click')
    await flushPromises()
    expect(mockPublish).not.toHaveBeenCalled()
  })

  it('valid rows publish with the message name and correlation key', async () => {
    const w = render()
    await w.find('#msg-name').setValue('orderPaid')
    await w.find('#msg-correlation').setValue('corr-1')
    await flushPromises()
    const picker = w.findComponent(PresetPicker)
    await picker.findAll('button').find((b) => b.text().includes('presetAddVariable'))!.trigger('click')
    await picker.find('input[id^="pv-name-"]').setValue('n')
    await picker.find('select[id^="pv-type-"]').setValue('LONG')
    await picker.find('input[id^="pv-value-"]').setValue('7')
    await flushPromises()
    const publishBtn = w.findAll('button').find((b) => b.text().trim() === 'presetPublish')!
    expect((publishBtn.element as HTMLButtonElement).disabled).toBe(false)
    await publishBtn.trigger('click')
    await flushPromises()
    expect(mockPublish).toHaveBeenCalledTimes(1)
    expect(mockPublish).toHaveBeenCalledWith({
      messageName: 'orderPaid',
      correlationKey: 'corr-1',
      processInstanceId: 'pi1',
      variables: [{ name: 'n', type: 'LONG', value: '7' }],
    })
  })
})
