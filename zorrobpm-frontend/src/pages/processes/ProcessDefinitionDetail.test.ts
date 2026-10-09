// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { VueWrapper } from '@vue/test-utils'
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

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
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

// ——— WO-UI-27: хелперы портального стартового диалога (уровень модуля) ———
const mounted: VueWrapper[] = []

// WO-UI-27: стартовый диалог — shadcn-Dialog (портал в document.body).
function mountPage() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const w = mount(ProcessDefinitionDetail, {
    attachTo: host,
    global: { plugins: [createPinia()] },
  })
  mounted.push(w)
  return w
}

// WO-UI-27: портал Dialog монтируется асинхронно — ждём парой циклов.
async function settlePortal() {
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await flushPromises()
}

function cleanupMounts() {
  for (const w of mounted.splice(0)) w.unmount()
  document.body.innerHTML = ''
}

describe('ProcessDefinitionDetail render', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanupMounts()
  })


  /** VariablesEditor внутри стартовой модалки (портал — ищем по document). */
  function wFindEditor(wrapper: ReturnType<typeof mountPage>) {
    const eds = wrapper.findAllComponents({ name: 'VariablesEditor' })
    if (eds.length) return eds[eds.length - 1]
    throw new Error('VariablesEditor not found')
  }

  it('WO-UI-27 п.3: стартовый диалог по умолчанию ШИРОКИЙ min(94vw,1280px), тумблер Уже/Шире с памятью', async () => {
    localStorage.removeItem('zbpm-start-dialog-wide')
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await settlePortal()
    const dlg = document.querySelector('[role="dialog"]') as HTMLElement | null
    expect(dlg, 'start dialog renders in portal').not.toBeNull()
    // Дефолт — широко (владелец: «диалог можно сделать шире»).
    expect(dlg!.className).toContain('w-[min(94vw,1280px)]')
    // Тумблер сужает и запоминает выбор.
    ;([...document.querySelectorAll('button')].find((b) => (b.textContent ?? '').includes('presetDialogNarrower')) as HTMLElement).click()
    await settlePortal()
    expect((document.querySelector('[role="dialog"]') as HTMLElement).className).toContain('w-[min(94vw,640px)]')
    expect(localStorage.getItem('zbpm-start-dialog-wide')).toBe('0')
  })

  it('mounts without ReferenceError (dead ref removed in MT-9)', async () => {
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    // Should render without ReferenceError — previously 'authStore' was undefined
    expect(wrapper.text()).toContain('Test')
  })

  it('start modal renders the preset picker (manual input by default)', async () => {
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0));
    await (flushPromises as () => Promise<void>)()
    // WO-VT-1: инлайн-редактор старта заменён PresetPicker «Ручной ввод | Из шаблона».
    // WO-UI-27: диалог — shadcn-Dialog в портале document.
    expect(document.body.textContent).toContain('presetManualMode')
    expect(document.body.textContent).toContain('presetTemplateMode')
  })

  it('manual rows offer all six variable types', async () => {
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0));
    await (flushPromises as () => Promise<void>)()
    ;([...document.querySelectorAll('button')].find((b) => (b.textContent ?? '').includes('presetAddVariable')) as HTMLElement).click()
    await flushPromises()
    // WO-UI-27: тип — shadcn-Select; опции телепортированы в document.body.
    const typeTrigger = document.querySelector('[data-testid="ve-type-0"]') as HTMLElement
    expect(typeTrigger, 'type select renders in portal').not.toBeNull()
    typeTrigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0));
    await (flushPromises as () => Promise<void>)()
    const options = [...document.querySelectorAll('[role="option"]')].map((o) => o.textContent?.trim())
    for (const tp of ['STRING', 'UUID', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON']) {
      expect(options).toContain(tp)
    }
  })

  it('shows textarea when JSON type is selected', async () => {
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    await flushPromises();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await (flushPromises as () => Promise<void>)();
    ([...document.querySelectorAll('button')].find((b) => (b.textContent ?? '').includes('presetAddVariable')) as HTMLElement).click()
    await new Promise((r) => setTimeout(r, 0))
    await flushPromises()
    wFindEditor(wrapper).vm.$emit('update:modelValue', [
      { name: document.querySelector('input[id^="pv-name-"]') ? (document.querySelector('input[id^="pv-name-"]') as HTMLInputElement).value || 'x' : 'x', type: 'JSON', value: '', allowEmptyString: null },
    ])
    await flushPromises()
    // Should show textarea instead of input for value (портал — ищем в document).
    expect(document.querySelector('textarea[id^="pv-value-"]'), 'JSON textarea renders in portal').not.toBeNull()
  })

  it('rejects invalid JSON: row error shows and start stays disabled', async () => {
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    await flushPromises();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await (flushPromises as () => Promise<void>)();
    ([...document.querySelectorAll('button')].find((b) => (b.textContent ?? '').includes('presetAddVariable')) as HTMLElement).click()
    await flushPromises()
    ;(document.querySelector('input[id^="pv-name-"]') as HTMLInputElement).value = 'badJson'
    document.querySelector('input[id^="pv-name-"]')!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    // WO-UI-27: имя — через emit (shadcn-Input), значение — через emit тоже:
    // ручной input-event shadcn-Input глотает без v-model-синка в этом тесте.
    wFindEditor(wrapper).vm.$emit('update:modelValue', [
      { name: 'badJson', type: 'JSON', value: '{invalid}', allowEmptyString: null },
    ])
    await flushPromises()
    // WO-VT-1: плохой JSON подсвечен, запуск заблокирован (как раньше addVariable).
    expect(document.querySelector('[role="alert"]'), 'row error renders in portal').not.toBeNull()
    const startBtn = [...document.querySelectorAll('button')].find((b) => (b.textContent ?? '').trim() === 'startProcess') as HTMLButtonElement | undefined
    expect(startBtn, 'start button renders in portal').not.toBeUndefined()
    expect(startBtn!.disabled).toBe(true)
    expect(mockStartInstance).not.toHaveBeenCalled()
  })

  it('POF: start process with type=JSON sends type JSON in API payload', async () => {
    const wrapper = mountPage()
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as any
    vm.showStartModal = true
    await wrapper.vm.$nextTick()
    await flushPromises();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await (flushPromises as () => Promise<void>)();
    ([...document.querySelectorAll('button')].find((b) => (b.textContent ?? '').includes('presetAddVariable')) as HTMLElement).click()
    await flushPromises()
    wFindEditor(wrapper).vm.$emit('update:modelValue', [
      { name: 'data', type: 'JSON', value: '["u1","u2"]', allowEmptyString: null },
    ])
    await flushPromises()
    ;([...document.querySelectorAll('button')].find((b) => (b.textContent ?? '').trim() === 'startProcess') as HTMLElement).click()
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

    const wrapper = mountPage()
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

    const wrapper = mountPage()
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
