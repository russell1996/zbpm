// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'

// hoisted mock ref so tests can assert on startInstance calls
const mockStartInstance = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'inst-1' }))

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { id: 'def1' },
    path: '/processes/definitions/def1',
    fullPath: '/processes/definitions/def1',
    name: 'process-definition-detail',
  }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  saveElementSchema: vi.fn(),
  getForm: vi.fn(),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] },
    currentVersions: [],
    loading: false,
    error: null,
    fetchDefinition: vi.fn(),
    fetchStructure: vi.fn(),
    fetchVersions: vi.fn(),
    startInstance: mockStartInstance,
  }),
}))

describe('ProcessDefinitionDetail render', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('mounts without ReferenceError (dead ref removed in MT-9)', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    // Should render without ReferenceError — previously 'authStore' was undefined
    expect(wrapper.text()).toContain('Test')
  })

  it('start modal renders the preset picker (manual input by default)', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    // WO-VT-1: инлайн-редактор старта заменён PresetPicker «Ручной ввод | Из шаблона».
    expect(wrapper.text()).toContain('presetManualMode')
    expect(wrapper.text()).toContain('presetTemplateMode')
  })

  it('manual rows offer all six variable types', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('presetAddVariable'))!
    await addBtn.trigger('click')
    const options = wrapper.find('select[id^="pv-type-"]').findAll('option').map((o: any) => o.text())
    for (const tp of ['STRING', 'UUID', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON']) {
      expect(options).toContain(tp)
    }
  })

  it('shows textarea when JSON type is selected', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    await wrapper.findAll('button').find((b) => b.text().includes('presetAddVariable'))!.trigger('click')
    await wrapper.find('select[id^="pv-type-"]').setValue('JSON')
    // Should show textarea instead of input for value
    const textarea = wrapper.find('textarea[id^="pv-value-"]')
    expect(textarea.exists()).toBe(true)
  })

  it('rejects invalid JSON: row error shows and start stays disabled', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    await wrapper.findAll('button').find((b) => b.text().includes('presetAddVariable'))!.trigger('click')
    await wrapper.find('input[id^="pv-name-"]').setValue('badJson')
    await wrapper.find('select[id^="pv-type-"]').setValue('JSON')
    await wrapper.find('textarea[id^="pv-value-"]').setValue('{invalid}')
    // WO-VT-1: плохой JSON подсвечен, запуск заблокирован (как раньше addVariable).
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    const startBtn = wrapper.findAll('button').find((b) => b.text().trim() === 'startProcess')!
    expect((startBtn.element as HTMLButtonElement).disabled).toBe(true)
    expect(mockStartInstance).not.toHaveBeenCalled()
  })

  it('POF: start process with type=JSON sends type JSON in API payload', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    await wrapper.findAll('button').find((b) => b.text().includes('presetAddVariable'))!.trigger('click')
    await wrapper.find('input[id^="pv-name-"]').setValue('data')
    await wrapper.find('select[id^="pv-type-"]').setValue('JSON')
    await wrapper.find('textarea[id^="pv-value-"]').setValue('["u1","u2"]')
    await wrapper.findAll('button').find((b) => b.text().trim() === 'startProcess')!.trigger('click')
    await wrapper.vm.$nextTick()

    expect(mockStartInstance).toHaveBeenCalledTimes(1)
    const callArg = mockStartInstance.mock.calls[0][0]
    expect(callArg.variables).toContainEqual({ name: 'data', type: 'JSON', value: '["u1","u2"]' })
  })
})

/**
 * WO-FE-11: Download BPMN button
 */
describe('ProcessDefinitionDetail — download BPMN (WO-FE-11)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
  })

  // ──────────────────────────────────────────────
  // Criteria 1: Button exists and triggers download with correct filename
  // ──────────────────────────────────────────────
  it('GREEN: download BPMN button exists and triggers download with loaded XML', async () => {
    // Re-mock getProcessDefinitionXml to return XML so the component loads it
    const { getProcessDefinitionXml } = await import('@/services/processService')
    vi.mocked(getProcessDefinitionXml).mockResolvedValue('<definitions id="proc1" />')

    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    await flushPromises()

    // Button should be rendered with the i18n key (mock returns key as-is)
    const allButtons = wrapper.findAll('button')
    const downloadBtn = allButtons.find((b) => b.text().trim() === 'downloadBpmn')
    expect(downloadBtn, `Button 'downloadBpmn' not found. All buttons: ${allButtons.map((b) => `"${b.text().trim()}"`).join(', ')}`).toBeDefined()

    // Click the button
    await downloadBtn!.trigger('click')
    await wrapper.vm.$nextTick()

    // createObjectURL should have been called with a Blob containing the XML
    const createSpy = URL.createObjectURL as ReturnType<typeof vi.spyOn>
    expect(createSpy).toHaveBeenCalledTimes(1)
    const blobArg = createSpy.mock.calls[0][0] as Blob
    expect(blobArg).toBeInstanceOf(Blob)
    expect(blobArg.type).toBe('application/xml;charset=utf-8;')
  })

  // ──────────────────────────────────────────────
  // Criteria 2: If bpmnXml is empty, load via service first
  // ──────────────────────────────────────────────
  it('GREEN: download BPMN loads XML from service if not yet loaded', async () => {
    const { getProcessDefinitionXml } = await import('@/services/processService')
    // Reset to return null (as per top-level mock) — bpmnXml stays empty after mount
    vi.mocked(getProcessDefinitionXml).mockResolvedValue('')

    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await wrapper.vm.$nextTick()
    await flushPromises()

    const vm = wrapper.vm as any
    // Ensure bpmnXml is empty (service returned null during mount)
    expect(vm.bpmnXml).toBeFalsy()

    // Now set up mock for the download trigger
    vi.mocked(getProcessDefinitionXml).mockResolvedValue('<definitions id="lazy-loaded" />')

    // Click download — should trigger service fetch
    const allButtons = wrapper.findAll('button')
    const downloadBtn = allButtons.find((b) => b.text().trim() === 'downloadBpmn')
    expect(downloadBtn).toBeDefined()

    await downloadBtn!.trigger('click')
    await wrapper.vm.$nextTick()

    // Service was called to fetch XML
    expect(getProcessDefinitionXml).toHaveBeenCalledWith('def1')
  })
})
