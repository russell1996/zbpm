/**
 * WO-UI-34: браузерная проверка в реальном Chromium:
 * — вкладок «Ручной ввод | Из шаблона» нет, один дропдаун «Шаблон»;
 * — ровно один VariablesEditor; выбор шаблона заполняет ТОТ ЖЕ редактор;
 * — замена уже введённых строк — тихий тост с «Отменить» (undo возвращает);
 * — available=false — списка «Шаблон» нет, редактор остаётся;
 * — >8 шаблонов — Combobox с поиском (plain-Select — без поискового поля);
 * — стартовый диалог и сам пикер НЕ выше, чем старая конструкция
 *   (baseline старого дизайна — константы ниже, сняты ДО правки в этом же
 *   Chromium на той же фикстуре: 5 переменных, включая однострочный JSON stages).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { page } from 'vitest/browser'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import PresetPicker from '@/widgets/presets/PresetPicker.vue'
import VariablesEditor from '@/widgets/presets/VariablesEditor.vue'
import ProcessDefinitionDetail from '@/pages/processes/ProcessDefinitionDetail.vue'
import type { PresetVariable, VariablePreset } from '@/types/presets'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'
import '@/style.css'

/**
 * BASELINE: высоты СТАРОГО дизайна (табы + два редактора), сняты ДО правки
 * в этом же Chromium (1280×720, light, фикстура 5 переменных):
 *   pикер manual @520 = 213.5; pикер template @520 = 759.09375;
 *   pикер template @360 = 759.09375;
 *   start-диалог manual @1280 = 371.5; start-диалог template @1280 = 917.09375.
 * Новый дизайн обязан быть НЕ выше (manual) и заметно короче (template:
 * второй редактор убран — экономия не менее высоты редактора).
 */
const BASELINE_PICKER_MANUAL_520 = 213.5
const BASELINE_PICKER_TEMPLATE_520 = 759.09375
const BASELINE_PICKER_TEMPLATE_360 = 759.09375
const BASELINE_DIALOG_MANUAL_1280 = 371.5
const BASELINE_DIALOG_TEMPLATE_1280 = 917.09375

const FIVE: PresetVariable[] = [
  { name: 'createdEmployeeId', type: 'LONG', value: '21346' },
  { name: 'processTypeId', type: 'LONG', value: '1' },
  { name: 'sourceUuid', type: 'UUID', value: '01a0b481-3ff1-7334-82e0-9c1e5b2a3d4f' },
  { name: 'sourceVersion', type: 'LONG', value: '1' },
  {
    name: 'stages',
    type: 'JSON',
    value: JSON.stringify([
      { stageSequence: 1, employeeFirstName: 'Кирилл' },
      { stageSequence: 2, employeeFirstName: 'Анна' },
    ]),
  },
]

function preset(i: number): VariablePreset {
  return {
    id: `p${i}`,
    processDefinitionKey: 'test-proc',
    targetKind: 'START',
    targetRef: null,
    name: `Шаблон ${i}`,
    description: null,
    ownerUserId: 'u1',
    visibility: i % 2 ? 'PROCESS' : 'PRIVATE',
    variables: FIVE,
    favorite: false,
    createdAt: '2026-01-01',
    updatedAt: '2026-01-01',
    version: 1,
  }
}

const toastCalls = vi.hoisted(
  () => [] as Array<{ msg: string; opts?: { action?: { label: string; onClick: () => void } } }>,
)
const mockToastInfo = vi.hoisted(() =>
  vi.fn((msg: string, opts?: { action?: { label: string; onClick: () => void } }) => {
    toastCalls.push({ msg, opts })
  }),
)
const mockList = vi.hoisted(() => vi.fn())
const mockGet = vi.hoisted(() => vi.fn())
vi.mock('@/services/presetService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/presetService')>()),
  listPresets: mockList,
  getPreset: mockGet,
}))
vi.mock('@/services/formService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/formService')>()),
  getStartForm: vi.fn().mockResolvedValue({ type: 'none' }),
  getTaskForm: vi.fn().mockResolvedValue({ type: 'none' }),
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  listForms: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/processService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/processService')>()),
  getProcessDefinitionXml: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
}))
vi.mock('@/services/adminService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/adminService')>()),
  listMembers: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/instanceService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/instanceService')>()),
  startProcessInstance: vi.fn().mockResolvedValue({ id: 'inst-1' }),
  cancelProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1' }),
}))
vi.mock('@/services/variableService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/variableService')>()),
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/taskService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/taskService')>()),
  getUserTask: vi.fn().mockResolvedValue(null),
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
    startInstance: vi.fn().mockResolvedValue({ id: 'inst-1' }),
  }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u1', username: 'op' }, isSuperAdmin: true }),
}))
vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'def1' }, path: '/processes/definitions/def1', fullPath: '/processes/definitions/def1', name: 'process-definition-detail' }),
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: mockToastInfo, warning: vi.fn(), loading: vi.fn() }),
}))

function makeI18n(locale = 'ru') {
  return createI18n({ legacy: false, locale, fallbackLocale: 'en', messages: { ru, en, kz } })
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

function attachHost(width: number): HTMLElement {
  const host = document.createElement('div')
  host.style.width = `${width}px`
  host.style.minHeight = '200px'
  host.style.background = '#fff'
  host.style.colorScheme = 'light'
  document.body.appendChild(host)
  hosts.push(host)
  return host
}

async function settle() {
  await flushPromises()
  await new Promise((r) => setTimeout(r, 150))
  await flushPromises()
}

/** Новый дизайн: открыть дропдаун «Шаблон» и выбрать пункт по индексу (0 = «Без шаблона»). */
async function pickOption(w: VueWrapper, optionIndex: number) {
  const trigger = w.find('[data-testid="preset-select"]')
  expect(trigger, 'preset-select trigger renders').toBeTruthy()
  trigger.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
  await settle()
  const opts = document.querySelectorAll('[role="option"]')
  expect(opts.length, `options rendered (need ${optionIndex + 1})`).toBeGreaterThan(optionIndex)
  ;(opts[optionIndex] as HTMLElement).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
  await settle()
}

function editorNames(w: VueWrapper): string[] {
  return w.findAll('input[id^="pv-name-"]').map((el) => (el.element as HTMLInputElement).value)
}

/** Добавить ручную строку: имя + значение (значение — input ИЛИ textarea). */
async function addManualRow(w: VueWrapper, name: string, value: string) {
  const addBtn = w.findAll('button').find((b) => /Добавить переменную|Add variable/.test(b.text()))
  expect(addBtn, 'add-variable button renders').toBeTruthy()
  await addBtn!.trigger('click')
  await settle()
  const names = w.findAll('input[id^="pv-name-"]')
  await names[names.length - 1].setValue(name)
  const n = names.length - 1
  await w.find(`#pv-value-${n}`).setValue(value)
  await settle()
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  vi.clearAllMocks()
  toastCalls.length = 0
  mockList.mockResolvedValue([preset(1), preset(2)])
  mockGet.mockImplementation(async (id: string) => ({ ...preset(1), id, variables: FIVE }))
})

afterEach(async () => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
  await page.viewport(1280, 720)
})

describe('WO-UI-34: один дропдаун «Шаблон» вместо вкладок (Chromium)', () => {
  it('вкладок режима нет; один дропдаун, первый пункт «Без шаблона»', async () => {
    await page.viewport(1280, 720)
    const host = attachHost(520)
    const w = mount(PresetPicker, {
      attachTo: host,
      props: { processKey: 'test-proc', targetKind: 'START' } as never,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w)
    await settle()
    const tabTexts = w.findAll('[role="tab"]').map((t) => t.text())
    expect(tabTexts.some((x) => /Ручной ввод|Из шаблона/.test(x)), 'no mode tabs').toBe(false)
    expect(w.find('[data-testid="preset-select"]').exists()).toBe(true)
    // Открыть и проверить первый пункт.
    const trigger = w.find('[data-testid="preset-select"]')
    trigger.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await settle()
    const first = document.querySelector('[role="option"]')
    expect(first?.textContent).toContain('Без шаблона')
    // Триггер по умолчанию показывает «Без шаблона — ввести вручную».
    expect(trigger.text()).toContain('Без шаблона')
  })

  it('выбор шаблона заполняет ТОТ ЖЕ редактор; редакторов ровно один', async () => {
    await page.viewport(1280, 720)
    const host = attachHost(520)
    const w = mount(PresetPicker, {
      attachTo: host,
      props: { processKey: 'test-proc', targetKind: 'START' } as never,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w)
    await settle()
    expect(w.findAllComponents(VariablesEditor).length).toBe(1)
    await pickOption(w, 1)
    expect(w.findAllComponents(VariablesEditor).length).toBe(1)
    expect(editorNames(w)).toEqual(FIVE.map((v) => v.name))
    // Триггер показывает имя выбранного шаблона.
    expect(w.find('[data-testid="preset-select"]').text()).toContain('Шаблон 1')
  })

  it('замена ручных строк шаблоном — тихий тост с «Отменить»; undo возвращает строки', async () => {
    await page.viewport(1280, 720)
    const host = attachHost(520)
    const w = mount(PresetPicker, {
      attachTo: host,
      props: { processKey: 'test-proc', targetKind: 'START' } as never,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w)
    await settle()
    await addManualRow(w, 'm', '7')
    expect(editorNames(w)).toEqual(['m'])
    await pickOption(w, 1)
    expect(editorNames(w)).toEqual(FIVE.map((v) => v.name))
    expect(toastCalls.length).toBe(1)
    expect(toastCalls[0].msg).toBe('Шаблон применён')
    expect(toastCalls[0].opts?.action?.label).toBe('Отменить')
    toastCalls[0].opts!.action!.onClick()
    await settle()
    expect(editorNames(w)).toEqual(['m'])
  })

  it('available=false: списка «Шаблон» нет, редактор остаётся', async () => {
    await page.viewport(1280, 720)
    // Реальный формат «флаг выключен»: 404 с сообщением про флаг
    // (isPresetsDisabled в presetService сверяет status + текст).
    mockList.mockRejectedValue({
      response: { status: 404, data: { message: 'Variable presets are disabled' } },
    })
    const host = attachHost(520)
    const w = mount(PresetPicker, {
      attachTo: host,
      props: { processKey: 'test-proc', targetKind: 'START' } as never,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w)
    await settle()
    expect(w.find('[data-testid="preset-select"]').exists()).toBe(false)
    expect(w.findAllComponents(VariablesEditor).length).toBe(1)
  })

  it('>8 шаблонов — Combobox с поиском; поиск фильтрует; без горизонтального оверфлоу', async () => {
    await page.viewport(1280, 720)
    mockList.mockResolvedValue(Array.from({ length: 10 }, (_, i) => preset(i + 1)))
    const host = attachHost(520)
    const w = mount(PresetPicker, {
      attachTo: host,
      props: { processKey: 'test-proc', targetKind: 'START' } as never,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w)
    await settle()
    const trigger = w.find('[data-testid="preset-select"]')
    // В Chromium Combobox открывается реальным кликом (клавиатура — запасной путь).
    await trigger.trigger('click')
    await settle()
    let search = document.querySelector('input[placeholder="Поиск шаблона…"]') as HTMLInputElement | null
    if (!search) {
      const el = trigger.element as HTMLElement
      el.focus()
      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }))
      await settle()
      search = document.querySelector('input[placeholder="Поиск шаблона…"]') as HTMLInputElement | null
    }
    expect(search, 'combobox search input for >8 presets').not.toBeNull()
    expect(document.querySelectorAll('[role="option"]').length).toBe(11) // 1 + 10
    search!.value = 'Шаблон 10'
    search!.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    const opts = [...document.querySelectorAll('[role="option"]')]
    expect(opts).toHaveLength(1)
    expect(opts[0].textContent).toContain('Шаблон 10')
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(1281)
  })

  it('≤8 шаблонов — plain Select без поискового поля', async () => {
    await page.viewport(1280, 720)
    mockList.mockResolvedValue(Array.from({ length: 8 }, (_, i) => preset(i + 1)))
    const host = attachHost(520)
    const w = mount(PresetPicker, {
      attachTo: host,
      props: { processKey: 'test-proc', targetKind: 'START' } as never,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w)
    await settle()
    const trigger = w.find('[data-testid="preset-select"]')
    trigger.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await settle()
    expect(document.querySelector('input[placeholder="Поиск шаблона…"]')).toBeNull()
    expect(document.querySelectorAll('[role="option"]').length).toBe(9) // 1 + 8
  })

  it('пикер не выше старого дизайна: manual @520; template @520 и @360 — заметно короче', async () => {
    await page.viewport(1280, 720)
    const host = attachHost(520)
    const w = mount(PresetPicker, {
      attachTo: host,
      props: { processKey: 'test-proc', targetKind: 'START' } as never,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w)
    await settle()
    const manualH = (w.element as HTMLElement).getBoundingClientRect().height
    expect(manualH, `new=${manualH} baseline=${BASELINE_PICKER_MANUAL_520}`).toBeLessThanOrEqual(BASELINE_PICKER_MANUAL_520 + 1)
    await pickOption(w, 1)
    const templateH520 = (w.element as HTMLElement).getBoundingClientRect().height
    expect(
      templateH520,
      `new=${templateH520} baseline=${BASELINE_PICKER_TEMPLATE_520}`,
    ).toBeLessThan(BASELINE_PICKER_TEMPLATE_520 - 30)
    // @360: та же фикстура, без оверфлоу.
    w.unmount()
    mounted = []
    host.remove()
    hosts = []
    const hostNarrow = attachHost(360)
    const w2 = mount(PresetPicker, {
      attachTo: hostNarrow,
      props: { processKey: 'test-proc', targetKind: 'START' } as never,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w2)
    await settle()
    await pickOption(w2, 1)
    const templateH360 = (w2.element as HTMLElement).getBoundingClientRect().height
    expect(
      templateH360,
      `new=${templateH360} baseline=${BASELINE_PICKER_TEMPLATE_360}`,
    ).toBeLessThan(BASELINE_PICKER_TEMPLATE_360 - 30)
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(1281)
  })

  it('стартовый диалог не выше старого дизайна: manual @1280; template @1280 — заметно короче', async () => {
    await page.viewport(1280, 800)
    const host = attachHost(1280)
    const w = mount(ProcessDefinitionDetail, {
      attachTo: host,
      global: { plugins: [createPinia(), makeI18n('ru')] },
    }) as unknown as VueWrapper
    mounted.push(w)
    await settle()
    const vm = w.vm as unknown as { showStartModal: boolean }
    vm.showStartModal = true
    await settle()
    const dlg = document.querySelector('[role="dialog"]') as HTMLElement | null
    expect(dlg, 'start dialog in portal').not.toBeNull()
    const manualH = dlg!.getBoundingClientRect().height
    expect(manualH, `new=${manualH} baseline=${BASELINE_DIALOG_MANUAL_1280}`).toBeLessThanOrEqual(BASELINE_DIALOG_MANUAL_1280 + 1)
    const picker = w.findComponent(PresetPicker)
    expect(picker.exists()).toBe(true)
    await pickOption(picker, 1)
    const templateH = dlg!.getBoundingClientRect().height
    expect(
      templateH,
      `new=${templateH} baseline=${BASELINE_DIALOG_TEMPLATE_1280}`,
    ).toBeLessThan(BASELINE_DIALOG_TEMPLATE_1280 - 30)
  })
})
