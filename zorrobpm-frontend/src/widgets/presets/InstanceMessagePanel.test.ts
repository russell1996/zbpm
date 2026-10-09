// @vitest-environment jsdom
// jsdom lacks PointerEvent capture APIs that reka-ui's SelectTrigger calls on pointerdown.
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}

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
    // NOTE: значение правится через emit редактора (shadcn-Textarea не
    // отдаёт DOM value для setValue как нативный textarea).
    const editor = picker.findComponent({ name: 'VariablesEditor' })
    editor.vm.$emit('update:modelValue', [{ name: 'cfg', type: 'JSON', value: '{invalid}', allowEmptyString: null }])
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
    const editor = picker.findComponent({ name: 'VariablesEditor' })
    editor.vm.$emit('update:modelValue', [{ name: 'n', type: 'LONG', value: '7', allowEmptyString: null }])
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
