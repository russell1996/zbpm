// @vitest-environment jsdom
/**
 * WO-VT-1 раунд 2 (Б-1, Б-2): проводка пикер→сервис в ServiceTaskDetail.
 * - complete/fail/throw-error шлют переменные из пикера (с развёрнутыми
 *   плейсхолдерами);
 * - fallback без пикера шлёт legacy editableVars (Б-1: раньше уходило []);
 * - ask-поля и невалидные строки блокируют действие.
 * Мутация `pickerVariables() → []` обязана краснить этот файл (Б-2).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ServiceTaskDetail from './ServiceTaskDetail.vue'
import PresetPicker from '@/widgets/presets/PresetPicker.vue'
import { Button } from '@/components/ui/button'
import { resetPresetSeqCounter } from '@/shared/lib/presetVariables'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'st1' }, path: '/service-tasks/st1', fullPath: '/service-tasks/st1', name: 'service-task-detail' }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

const mockComplete = vi.hoisted(() => vi.fn())
const mockFail = vi.hoisted(() => vi.fn())
const mockThrow = vi.hoisted(() => vi.fn())
const mockGetServiceTask = vi.hoisted(() => vi.fn())
vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn().mockResolvedValue({ data: [] }),
  getUserTask: vi.fn(),
  completeUserTask: vi.fn(),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [] }),
  getServiceTask: mockGetServiceTask,
  completeServiceTask: mockComplete,
  failServiceTask: mockFail,
  throwServiceTaskError: mockThrow,
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

const mockFetchDefinition = vi.hoisted(() => vi.fn())
const mockProcessStoreState = vi.hoisted(() => ({
  currentDefinition: null as null | { id: string; key: string },
}))
vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: mockProcessStoreState.currentDefinition,
    fetchDefinition: mockFetchDefinition,
  }),
}))

const mockListPresets = vi.hoisted(() => vi.fn())
const mockGetPreset = vi.hoisted(() => vi.fn())
vi.mock('@/services/presetService', () => ({
  listPresets: mockListPresets,
  getPreset: mockGetPreset,
  createPreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn(),
  importPreset: vi.fn(),
  exportPreset: vi.fn(),
  getPresetHistory: vi.fn().mockResolvedValue([]),
  setPresetFavorite: vi.fn(),
  changePresetVisibility: vi.fn(),
  isPresetsDisabled: () => false,
  isPresetConflict: () => false,
  presetErrorCode: () => null,
}))

const SERVICE_TASK = {
  id: 'st1', code: 'svc1', name: 'Svc', processInstanceId: 'pi1',
  processDefinitionId: 'pd-1', job: 'http', status: 'CREATED',
  createdAt: '2026-01-01T00:00:00Z', completedAt: null,
}

function renderPage() {
  return mount(ServiceTaskDetail, {
    global: { stubs: { teleport: true }, plugins: [createPinia()] },
  })
}

async function renderWithPicker() {
  mockProcessStoreState.currentDefinition = { id: 'pd-1', key: 'order' }
  mockFetchDefinition.mockResolvedValue(undefined)
  const w = renderPage()
  await flushPromises()
  expect(w.findComponent(PresetPicker).exists()).toBe(true)
  return w
}

async function typeManualRow(
  w: ReturnType<typeof renderPage>,
  name: string,
  type: string,
  value: string,
) {
  const picker = w.findComponent(PresetPicker)
  await picker.findAll('button').find((b) => b.text().includes('presetAddVariable'))!.trigger('click')
  // Строки добавляются в конец — правим ПОСЛЕДНЮЮ (find вернул бы первую).
  const names = picker.findAll('input[id^="pv-name-"]')
  const types = picker.findAll('select[id^="pv-type-"]')
  await names[names.length - 1].setValue(name)
  await types[types.length - 1].setValue(type)
  await flushPromises()
  // Контрол значения последней строки (input или textarea для JSON).
  const values = picker.findAll('input[id^="pv-value-"], textarea[id^="pv-value-"]')
  await values[values.length - 1].setValue(value)
  await flushPromises()
}

function actionButton(w: ReturnType<typeof renderPage>) {
  // На странице ДВА Button (шапочный «Complete» и нижний action над
  // переменными) — нужен последний, иначе клики уходят в complete().
  const all = w.findAllComponents(Button)
  return all[all.length - 1]
}

describe('ServiceTaskDetail — preset picker wiring (WO-VT-1 Б-2)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    resetPresetSeqCounter()
    mockGetServiceTask.mockResolvedValue({ ...SERVICE_TASK })
    mockComplete.mockResolvedValue({ id: 'done' })
    mockFail.mockResolvedValue({ id: 'done' })
    mockThrow.mockResolvedValue({ id: 'done' })
    mockListPresets.mockResolvedValue([])
    mockGetPreset.mockResolvedValue(null)
    mockProcessStoreState.currentDefinition = null
  })

  it('complete sends manually typed picker rows to the service', async () => {
    const w = await renderWithPicker()
    await typeManualRow(w, 'n', 'LONG', '7')
    await actionButton(w).trigger('click')
    await flushPromises()
    expect(mockComplete).toHaveBeenCalledTimes(1)
    expect(mockComplete).toHaveBeenCalledWith('st1', {
      variables: [{ name: 'n', type: 'LONG', value: '7' }],
    })
  })

  it('complete expands template placeholders before sending', async () => {
    mockListPresets.mockResolvedValue([{ id: 'p1', name: 'base', visibility: 'PRIVATE' }])
    mockGetPreset.mockResolvedValue({
      id: 'p1', variables: [
        { name: 'u', type: 'UUID', value: '{{uuid}}' },
        { name: 's', type: 'LONG', value: '{{seq}}' },
      ],
    })
    const w = await renderWithPicker()
    const picker = w.findComponent(PresetPicker)
    await picker.findAll('[role="tab"]')[1].trigger('click')
    await flushPromises()
    await picker.find('#preset-select').setValue('p1')
    await flushPromises()
    await actionButton(w).trigger('click')
    await flushPromises()
    expect(mockComplete).toHaveBeenCalledTimes(1)
    const sent = mockComplete.mock.calls[0][1].variables as Array<{ name: string; value: string }>
    expect(sent.find((v) => v.name === 'u')!.value).toMatch(/^[0-9a-f-]{36}$/)
    expect(sent.find((v) => v.name === 's')!.value).toBe('1')
  })

  it('fail sends the message together with picker variables', async () => {
    const w = await renderWithPicker()
    await typeManualRow(w, 'n', 'LONG', '7')
    await w.findAll('button').find((b) => b.text().trim() === 'serviceTaskFail')!.trigger('click')
    await w.find('#st-fail-message').setValue('boom')
    await actionButton(w).trigger('click')
    await flushPromises()
    expect(mockFail).toHaveBeenCalledTimes(1)
    expect(mockFail).toHaveBeenCalledWith('st1', 'boom', [{ name: 'n', type: 'LONG', value: '7' }])
  })

  it('throw-error sends the error code together with picker variables', async () => {
    const w = await renderWithPicker()
    await typeManualRow(w, 'n', 'LONG', '7')
    await w.findAll('button').find((b) => b.text().trim() === 'serviceTaskThrowError')!.trigger('click')
    await w.find('#st-error-code').setValue('ERR_X')
    await actionButton(w).trigger('click')
    await flushPromises()
    expect(mockThrow).toHaveBeenCalledTimes(1)
    expect(mockThrow).toHaveBeenCalledWith('st1', 'ERR_X', [{ name: 'n', type: 'LONG', value: '7' }])
  })

  it('throw-error without a code is blocked (service never called)', async () => {
    const w = await renderWithPicker()
    await typeManualRow(w, 'n', 'LONG', '7')
    await w.findAll('button').find((b) => b.text().trim() === 'serviceTaskThrowError')!.trigger('click')
    await flushPromises()
    expect((actionButton(w).element as HTMLButtonElement).disabled).toBe(true)
    expect(mockThrow).not.toHaveBeenCalled()
  })

  it('ask-at-launch rows block the action until filled', async () => {
    const w = await renderWithPicker()
    await typeManualRow(w, 'n', 'LONG', '7')
    await typeManualRow(w, 'ask', 'UUID', '')
    await flushPromises()
    expect((actionButton(w).element as HTMLButtonElement).disabled).toBe(true)
    expect(mockComplete).not.toHaveBeenCalled()
  })

  it('invalid rows block the action (service never called)', async () => {
    const w = await renderWithPicker()
    // NOTE: LONG/DOUBLE рендерят <input type="number"> — DOM-санитизация
    // режет нечисловой ввод в '' (ask-путь), поэтому невалидный (не ask)
    // ввод проверяется через JSON-textarea: '{invalid}' даёт
    // pickerInvalid=true при пустом askMissing.
    const picker = w.findComponent(PresetPicker)
    await picker.findAll('button').find((b) => b.text().includes('presetAddVariable'))!.trigger('click')
    const names = picker.findAll('input[id^="pv-name-"]')
    const types = picker.findAll('select[id^="pv-type-"]')
    await names[names.length - 1].setValue('cfg')
    await types[types.length - 1].setValue('JSON')
    await flushPromises()
    const areas = picker.findAll('textarea[id^="pv-value-"]')
    await areas[areas.length - 1].setValue('{invalid}')
    await flushPromises()
    const pvm = picker.vm as unknown as { missingAsk: string[]; hasErrors: boolean }
    expect(pvm.missingAsk).toEqual([])
    expect(pvm.hasErrors).toBe(true)
    expect((actionButton(w).element as HTMLButtonElement).disabled).toBe(true)
    expect(mockComplete).not.toHaveBeenCalled()
  })
})

describe('ServiceTaskDetail — legacy fallback without picker (WO-VT-1 Б-1)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockGetServiceTask.mockResolvedValue({ ...SERVICE_TASK })
    mockComplete.mockResolvedValue({ id: 'done' })
    mockProcessStoreState.currentDefinition = null
  })

  it('edited legacy variables reach complete (not an empty array)', async () => {
    const { getVariables } = await import('@/services/variableService')
    vi.mocked(getVariables).mockResolvedValue({
      data: [{ name: 'n', type: 'LONG', value: '1' }],
      totalElements: 1, pageIndex: 0, pageSize: 50,
    })
    mockFetchDefinition.mockRejectedValue(new Error('definition unavailable'))
    const w = renderPage()
    await flushPromises()
    // Пикера нет — рендерится legacy-редактор.
    expect(w.findComponent(PresetPicker).exists()).toBe(false)
    const legacyInput = w.findAll('input').find((i) => !(i.attributes('id') || '').startsWith('st-'))!
    await legacyInput.setValue('2')
    await actionButton(w).trigger('click')
    await flushPromises()
    expect(mockComplete).toHaveBeenCalledTimes(1)
    expect(mockComplete).toHaveBeenCalledWith('st1', {
      variables: [{ name: 'n', type: 'LONG', value: '2' }],
    })
  })
})
