// @vitest-environment jsdom
// jsdom lacks PointerEvent capture APIs that reka-ui's SelectTrigger calls on pointerdown.
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}
// jsdom lacks scrollIntoView — reka-ui's Listbox/Combobox calls it on item focus.
if (!HTMLElement.prototype.scrollIntoView) {
  HTMLElement.prototype.scrollIntoView = () => {}
}

/**
 * WO-UI-34: PresetPicker — одна строка «Шаблон» (shadcn Select, при >8
 * шаблонов — Combobox с поиском) вместо вкладок «Ручной ввод | Из шаблона»;
 * ОДИН VariablesEditor; выбор шаблона заполняет тот же редактор, замена
 * уже введённых строк — с тихим тостом «Отменить» (undo возвращает прежние).
 * Контракт 8 потребителей не меняется: getVariables/missingAsk/canApply/
 * hasErrors/available/mode, события change/templateSaved.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PresetPicker from './PresetPicker.vue'
import VariablesEditor from './VariablesEditor.vue'

const toastCalls = vi.hoisted(
  () => [] as Array<{ msg: string; opts?: { action?: { label: string; onClick: () => void } } }>,
)
const mockToast = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn((msg: string, opts?: { action?: { label: string; onClick: () => void } }) => {
    toastCalls.push({ msg, opts })
  }),
  warning: vi.fn(),
  loading: vi.fn(),
}))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

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

function preset(i: number) {
  return {
    id: `p${i}`,
    processDefinitionKey: 'k',
    targetKind: 'START',
    targetRef: null,
    name: `Шаблон ${i}`,
    description: null,
    variables: [
      { name: 'n', type: 'LONG', value: '7' },
      { name: 'ask', type: 'UUID', value: '' },
    ],
    ownerUserId: 'u1',
    visibility: i % 2 ? 'PROCESS' : 'PRIVATE',
    favorite: i === 1,
    createdAt: '2026-01-01',
    updatedAt: '2026-01-01',
    version: 1,
  }
}

function render(props: Record<string, unknown> = {}) {
  return mount(PresetPicker, {
    props: { processKey: 'k', targetKind: 'START', ...props },
  })
}

/** Открыть dropdown и выбрать опцию по индексу (0 = «Без шаблона»). */
async function pickOption(w: ReturnType<typeof render>, optionIndex: number) {
  const trigger = w.find('[data-testid="preset-select"]')
  expect(trigger, 'preset-select trigger renders').toBeTruthy()
  trigger.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
  await flushPromises()
  await new Promise((r) => setTimeout(r, 50))
  const opts = document.querySelectorAll('[role="option"]')
  expect(opts.length, `options rendered (need ${optionIndex + 1})`).toBeGreaterThan(optionIndex)
  ;(opts[optionIndex] as HTMLElement).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
  await flushPromises()
  await new Promise((r) => setTimeout(r, 50))
}

/** Добавить ручную строку через редактор: имя + значение.
 *  Значение — по id `#pv-value-{i}`: для STRING/JSON это <textarea>, не <input>. */
async function addManualRow(w: ReturnType<typeof render>, name: string, value: string) {
  const addBtn = w.findAll('button').find((b) => b.text().includes('presetAddVariable'))
  expect(addBtn, 'add-variable button renders').toBeTruthy()
  await addBtn!.trigger('click')
  await flushPromises()
  const names = w.findAll('input[id^="pv-name-"]')
  await names[names.length - 1].setValue(name)
  const n = names.length - 1
  await w.find(`#pv-value-${n}`).setValue(value)
  await flushPromises()
}

function vm(w: ReturnType<typeof render>) {
  return w.vm as unknown as {
    mode: string
    missingAsk: string[]
    canApply: boolean
    hasErrors: boolean
    available: boolean
    getVariables: () => Array<{ name: string; value: string }>
  }
}

function editorNames(w: ReturnType<typeof render>): string[] {
  return w.findAll('input[id^="pv-name-"]').map((el) => (el.element as HTMLInputElement).value)
}

describe('PresetPicker — WO-UI-34 single dropdown', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    toastCalls.length = 0
    document.body.innerHTML = ''
    mockList.mockResolvedValue([preset(1), preset(2)])
    mockGet.mockImplementation(async (id: string) => ({ ...preset(1), id }))
  })

  it('(a) no mode tabs — one «Шаблон» select, first option «Без шаблона»', async () => {
    const w = render()
    await flushPromises()
    const tabs = w.findAll('[role="tab"]').map((t) => t.text())
    expect(tabs.some((x) => x.includes('presetManualMode') || x.includes('presetTemplateMode'))).toBe(false)
    expect(w.find('[data-testid="preset-select"]').exists()).toBe(true)
    // Первый пункт — «Без шаблона — ввести вручную».
    const trigger = w.find('[data-testid="preset-select"]')
    trigger.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    const first = document.querySelector('[role="option"]')
    expect(first?.textContent).toContain('presetNoTemplate')
  })

  it('(e) exactly one VariablesEditor in the DOM (default и с выбранным шаблоном)', async () => {
    const w = render()
    await flushPromises()
    expect(w.findAllComponents(VariablesEditor).length).toBe(1)
    await pickOption(w, 1)
    expect(w.findAllComponents(VariablesEditor).length).toBe(1)
  })

  it('(b) selecting a template fills the same editor; edits survive, getVariables returns them', async () => {
    const w = render()
    await flushPromises()
    await pickOption(w, 1)
    expect(editorNames(w)).toEqual(['n', 'ask'])
    // Правка строки в том же редакторе.
    await w.find('input[id="pv-value-0"]').setValue('9')
    const vars = vm(w).getVariables()
    expect(vars.find((x) => x.name === 'n')?.value).toBe('9')
    expect(vars.find((x) => x.name === 'ask')?.value).toBe('')
    expect(vm(w).mode).toBe('template')
  })

  it('(c) selecting a template over manual rows shows a quiet toast; undo restores the manual rows', async () => {
    const w = render()
    await flushPromises()
    await addManualRow(w, 'm', '7')
    await pickOption(w, 1)
    expect(toastCalls.length).toBe(1)
    expect(toastCalls[0].msg).toBe('presetTemplateApplied')
    expect(toastCalls[0].opts?.action?.label).toBe('presetUndo')
    // В редакторе теперь строки шаблона.
    expect(editorNames(w)).toEqual(['n', 'ask'])
    // Undo возвращает прежний ручной ввод и режим.
    toastCalls[0].opts!.action!.onClick()
    await flushPromises()
    expect(editorNames(w)).toEqual(['m'])
    expect(vm(w).mode).toBe('manual')
    expect(vm(w).getVariables().map((v) => `${v.name}=${v.value}`)).toEqual(['m=7'])
  })

  it('(c-0) applying a template over an empty editor does not toast', async () => {
    const w = render()
    await flushPromises()
    await pickOption(w, 1)
    expect(toastCalls.length).toBe(0)
    expect(editorNames(w)).toEqual(['n', 'ask'])
  })

  // WO-UI-38: выбор «Без шаблона» — это НЕ потеря данных. Пользователь сам
  // сказал «ручной ввод», поэтому строки остаются (как ручные), тост «Шаблон
  // снят · Отменить» не показывается, ключ presetTemplateCleared удалён.
  it('(c-bis) switching back to «Без шаблона» keeps the rows as manual input and shows NO toast', async () => {
    const w = render()
    await flushPromises()
    await pickOption(w, 1)
    expect(toastCalls.length).toBe(0)
    await pickOption(w, 0)
    // Строки НЕ очищаются — они остаются ручным вводом.
    expect(editorNames(w)).toEqual(['n', 'ask'])
    expect(vm(w).mode).toBe('manual')
    // Тостов нет вообще: пользователь сам выбрал «без шаблона».
    expect(toastCalls.length).toBe(0)
    expect(toastCalls.map((c) => c.msg)).not.toContain('presetTemplateCleared')
  })

  // WO-UI-38: закрытое значение не должно склеивать имя шаблона и его
  // видимость («Start ApprovalУчастники процесса» → «Start Approval · …»).
  it('(b) closed value separates the template name and its visibility with «·», not glued', async () => {
    const w = render()
    await flushPromises()
    await pickOption(w, 1)
    const trigger = w.find('[data-testid="preset-select"]')
    const text = trigger.text()
    expect(text, 'name is shown').toContain('Шаблон 1')
    expect(text, 'visibility is shown').toContain('presetVisibilityProcess')
    expect(text, 'fragments are separated by «·»').toContain('·')
    expect(text, 'fragments are NOT glued').not.toContain('Шаблон 1presetVisibilityProcess')
    // Полный текст — по наведению (native title), обрезанный — с многоточием.
    expect(trigger.attributes('title')).toContain('Шаблон 1')
    expect(trigger.attributes('title')).toContain('presetVisibilityProcess')
  })

  it('(b-bis) the list item also separates the name and the visibility', async () => {
    const w = render()
    await flushPromises()
    const trigger = w.find('[data-testid="preset-select"]')
    trigger.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    const opt = document.querySelector('[data-testid="preset-opt-p2"]')
    expect(opt, 'preset option rendered').not.toBeNull()
    const text = opt!.textContent ?? ''
    expect(text).toContain('Шаблон 2')
    expect(text).toContain('presetVisibilityPrivate')
    expect(text, 'fragments are separated by «·»').toContain('·')
    expect(text, 'fragments are NOT glued').not.toContain('Шаблон 2presetVisibilityPrivate')
  })

  it('(b-ter) the Combobox closed value separates the name and the visibility too', async () => {
    mockList.mockResolvedValue(Array.from({ length: 10 }, (_, i) => preset(i + 1)))
    const w = render()
    await flushPromises()
    // jsdom: Combobox открывается кликом (не ArrowDown, как plain-Select).
    await w.find('[data-testid="preset-select"]').trigger('click')
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    const opts = document.querySelectorAll('[role="option"]')
    expect(opts.length, 'combobox options rendered').toBeGreaterThan(1)
    // reka ComboboxItem выбирает по click (ListboxItem onClick).
    ;(opts[1] as HTMLElement).dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    const trigger = w.find('[data-testid="preset-select"]')
    const text = trigger.text()
    expect(text).toContain('Шаблон 1')
    expect(text).toContain('presetVisibilityProcess')
    expect(text).toContain('·')
    expect(text).not.toContain('Шаблон 1presetVisibilityProcess')
  })

  it('(d) available=false: списка («Шаблон») нет, редактор остаётся', async () => {
    mockList.mockRejectedValue({ disabled: true })
    const w = render()
    await flushPromises()
    expect(w.find('[data-testid="preset-select"]').exists()).toBe(false)
    expect(w.findAllComponents(VariablesEditor).length).toBe(1)
    // «Сохранить как шаблон» — часть фичи шаблонов, тоже скрыта.
    expect(w.findAll('button').some((b) => b.text().includes('presetSaveAsTemplate'))).toBe(false)
  })

  it('more than 8 presets switch to the Combobox with search', async () => {
    mockList.mockResolvedValue(Array.from({ length: 10 }, (_, i) => preset(i + 1)))
    const w = render()
    await flushPromises()
    // Открыть дропдаун: поисковый input присутствует (у plain-Select его нет).
    await w.find('[data-testid="preset-select"]').trigger('click')
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    const search = document.querySelector('input[placeholder="presetSearchTemplates"]') as HTMLInputElement | null
    expect(search, 'combobox search input renders for >8 presets').not.toBeNull()
    // Пустой запрос: все 11 пунктов (1 «Без шаблона» + 10 шаблонов).
    expect(document.querySelectorAll('[role="option"]').length).toBe(11)
    search!.value = 'Шаблон 10'
    search!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    // Поиск по имени: остался только «Шаблон 10», «Без шаблона» скрыт.
    const opts = [...document.querySelectorAll('[role="option"]')].map((o) => o.textContent ?? '')
    expect(opts).toHaveLength(1)
    expect(opts[0]).toContain('Шаблон 10')
    // Выбрать отфильтрованный шаблон.
    const target = [...document.querySelectorAll('[role="option"]')].find((o) => (o.textContent ?? '').includes('Шаблон 10'))
    ;(target as HTMLElement).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    expect(mockGet).toHaveBeenCalledWith('p10')
    expect(editorNames(w)).toEqual(['n', 'ask'])
  })

  it('eight or fewer presets keep the plain Select (no search input)', async () => {
    mockList.mockResolvedValue(Array.from({ length: 8 }, (_, i) => preset(i + 1)))
    const w = render()
    await flushPromises()
    // jsdom: plain-Select открывается клавиатурой (как в pickOption),
    // клик на триггере в jsdom не раскрывает reka-Select.
    const trigger = w.find('[data-testid="preset-select"]')
    trigger.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    expect(document.querySelector('input[placeholder="presetSearchTemplates"]')).toBeNull()
    expect(document.querySelectorAll('[role="option"]').length).toBe(9) // 1 «Без шаблона» + 8
  })

  it('ask fields of the applied template still block apply; filling them unblocks', async () => {
    const w = render()
    await flushPromises()
    await pickOption(w, 1)
    expect(vm(w).missingAsk).toEqual(['ask'])
    expect(vm(w).canApply).toBe(false)
    await w.find('input[id="pv-value-1"]').setValue('123e4567-e89b-12d3-a456-426614174000')
    expect(vm(w).missingAsk).toEqual([])
    expect(vm(w).canApply).toBe(true)
    expect(vm(w).getVariables().find((x) => x.name === 'ask')?.value).toBe('123e4567-e89b-12d3-a456-426614174000')
  })

  it('ref change dropping the selected template resets to manual (contract)', async () => {
    const w = render()
    await flushPromises()
    await pickOption(w, 1)
    expect(vm(w).mode).toBe('template')
    mockList.mockResolvedValue([])
    await w.setProps({ targetRef: 'other' })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    expect(vm(w).mode).toBe('manual')
    expect(editorNames(w)).toEqual([])
    expect(w.emitted('change')?.at(-1)).toEqual([[]])
  })
})
