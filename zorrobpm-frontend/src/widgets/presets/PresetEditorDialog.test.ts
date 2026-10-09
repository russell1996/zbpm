// @vitest-environment jsdom
/**
 * WO-VT-1 (фронт, §1-бис п.2-бис): PresetEditorDialog — создание, правка с
 * version, конфликт 409 с перезагрузкой, дублирование, удаление с
 * подтверждением, избранное.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { VueWrapper } from '@vue/test-utils'
import { mount, flushPromises } from '@vue/test-utils'
import PresetEditorDialog from './PresetEditorDialog.vue'

const mockCreate = vi.hoisted(() => vi.fn())
const mockUpdate = vi.hoisted(() => vi.fn())
const mockDelete = vi.hoisted(() => vi.fn())
const mockGet = vi.hoisted(() => vi.fn())
const mockHistory = vi.hoisted(() => vi.fn())
const mockFavorite = vi.hoisted(() => vi.fn())
const mockVisibility = vi.hoisted(() => vi.fn())
vi.mock('@/services/presetService', () => ({
  createPreset: mockCreate,
  updatePreset: mockUpdate,
  deletePreset: mockDelete,
  getPreset: mockGet,
  getPresetHistory: mockHistory,
  setPresetFavorite: mockFavorite,
  changePresetVisibility: mockVisibility,
  isPresetConflict: (e: unknown) => (e as { conflict?: boolean })?.conflict === true,
  presetErrorCode: (e: unknown) => (e as { code?: string })?.code ?? null,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

/** Кнопки диалога живут в портале (document.body), не внутри wrapper. */
function docBtns() {
  return [...document.querySelectorAll('button')].map((el) => ({
    text: () => el.textContent ?? '',
    trigger: async (ev: string) => {
      el.dispatchEvent(new MouseEvent(ev === 'click' ? 'click' : ev, { bubbles: true }))
      await flushPromises()
    },
    element: el,
  }))
}

const PRESET = {
  id: 'p1',
  processDefinitionKey: 'k',
  targetKind: 'START',
  targetRef: null,
  name: 'base',
  description: '',
  variables: [{ name: 'n', type: 'LONG', value: '1' }],
  ownerUserId: 'u1',
  visibility: 'PRIVATE',
  favorite: false,
  createdAt: '2026-01-01',
  updatedAt: '2026-01-01',
  version: 3,
}

const mounted: VueWrapper[] = []

describe('PresetEditorDialog', () => {
  afterEach(() => {
    for (const w of mounted.splice(0)) w.unmount()
    document.body.innerHTML = ''
  })
  beforeEach(() => {
    vi.clearAllMocks()
    mockGet.mockResolvedValue({ ...PRESET })
    mockCreate.mockResolvedValue({ ...PRESET, id: 'p2' })
    mockUpdate.mockResolvedValue({ ...PRESET, version: 4 })
    mockDelete.mockResolvedValue(undefined)
    mockVisibility.mockImplementation(async (_id: string, v: string) => ({ ...PRESET, visibility: v, version: 4 }))
  })

  it('create: name + variables flow into createPreset, saved emitted', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
    global: { stubs: {} },
      props: { open: true, processKey: 'k', targetKind: 'START', targetRef: null },
    })
    mounted.push(w)
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    const nameInput = document.querySelector('#preset-name') as HTMLInputElement
    expect(nameInput, 'preset name field renders in portal').not.toBeNull()
    nameInput.value = 'case-a'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const addBtn = docBtns().find((b) => b.text().includes('presetAddVariable'))!
    await addBtn.trigger('click')
    // WO-VT-3: редактор в диалоге с id-префиксом pe- (без коллизий id).
    w.findComponent({ name: 'VariablesEditor' }).vm.$emit('update:modelValue', [
      { name: 'n', type: 'LONG', value: '7', allowEmptyString: null },
    ])
    await flushPromises()
    // WO-UI-27: тип — shadcn-Select; значение правим через emit редактора.
    w.findComponent({ name: 'VariablesEditor' }).vm.$emit('update:modelValue', [
      { name: 'n', type: 'LONG', value: '7', allowEmptyString: null },
    ])
    await flushPromises()
    const saveBtn = docBtns().find((b) => b.text().trim() === 'save')!
    await saveBtn.trigger('click')
    await flushPromises()
    expect(mockCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        processDefinitionKey: 'k',
        targetKind: 'START',
        name: 'case-a',
        variables: [{ name: 'n', type: 'LONG', value: '7' }],
      }),
    )
    expect(w.emitted('saved')).toBeTruthy()
  })

  it('WO-UI-27 BUG-2: Ctrl+Enter inside the dialog saves (keydown reaches content)', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
      global: { stubs: {} },
      props: { open: true, processKey: 'k', targetKind: 'START', targetRef: null },
    })
    mounted.push(w)
    await flushPromises()
    const nameInput = document.querySelector('#preset-name') as HTMLInputElement
    nameInput.value = 't1'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    // Ctrl+Enter изнутри диалога (портал) — должен всплыть до onDialogKeydown.
    // RED: @keydown висел на DialogScrollContent (корень — DialogPortal,
    // фрагмент) и терялся — save не вызывался.
    nameInput.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }),
    )
    await flushPromises()
    expect(mockCreate).toHaveBeenCalled()
  })

  it('WO-UI-27 RT-2b: Ctrl+Enter СНАРУЖИ фулскрина сначала коммитит его, потом сохраняет', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
      global: { stubs: {} },
      props: { open: true, processKey: 'k', targetKind: 'START', targetRef: null },
    })
    mounted.push(w)
    await flushPromises()
    const nameInput = document.querySelector('#preset-name') as HTMLInputElement
    nameInput.value = 't2'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    // JSON-строка с пустым значением, затем открываем фулскрин её кнопкой.
    w.findComponent({ name: 'VariablesEditor' }).vm.$emit('update:modelValue', [
      { name: 'j', type: 'JSON', value: '{"a":1}', allowEmptyString: null },
    ])
    await flushPromises()
    const fsBtn = [...document.querySelectorAll('button')].find((b) =>
      (b.textContent ?? '').includes('presetJsonFullscreen'),
    ) as HTMLButtonElement
    expect(fsBtn, 'fullscreen button renders').toBeTruthy()
    fsBtn.click()
    await flushPromises()
    const fsArea = document.querySelector('[id$="-jsonfs"]') as HTMLTextAreaElement
    expect(fsArea, 'fullscreen editor opens').not.toBeNull()
    // Набираем новое, НЕ нажимая «Применить» — фулскрин остаётся открыт.
    fsArea.value = '{"a":1,"b":2}'
    fsArea.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    // Ctrl+Enter по полю имени (снаружи фулскрина) — диалог обязан сначала
    // закоммитить фулскрин (commitFullscreen), иначе save уйдёт со старым.
    nameInput.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }),
    )
    await flushPromises()
    await flushPromises()
    await flushPromises()
    // RED-мутация: убрать commitFullscreen из ctrlEnterSave → createPreset
    // вызван со СТАРЫМ variables ({"a":1}), тест краснеет.
    expect(mockCreate).toHaveBeenCalledTimes(1)
    const payload = mockCreate.mock.calls[0][0] as { variables: Array<{ value: string }> }
    expect(JSON.parse(payload.variables[0].value)).toEqual({ a: 1, b: 2 })
  })

  it('edit: loads the preset and PUTs with version', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
    global: { stubs: {} },
      props: { open: true, processKey: 'k', targetKind: 'START', targetRef: null, presetId: 'p1' },
    })
    mounted.push(w)
    await flushPromises()
    expect(mockGet).toHaveBeenCalledWith('p1')
    expect((document.querySelector('#preset-name') as HTMLInputElement).value).toBe('base')
    const saveBtn = docBtns().find((b) => b.text().trim() === 'save')!
    await saveBtn.trigger('click')
    await flushPromises()
    expect(mockUpdate).toHaveBeenCalledWith('p1', expect.objectContaining({ version: 3 }))
  })

  it('409 with PRESET_CONFLICT shows the conflict banner and reload recovers', async () => {
    mockUpdate.mockRejectedValueOnce({ conflict: true, code: 'PRESET_CONFLICT' })
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
    global: { stubs: {} },
      props: { open: true, processKey: 'k', targetKind: 'START', targetRef: null, presetId: 'p1' },
    })
    mounted.push(w)
    await flushPromises()
    await docBtns().find((b) => b.text().trim() === 'save')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('presetVersionConflict')
    // перезагрузка актуальной версии скрывает баннер
    await docBtns().find((b) => b.text().includes('presetReloadLatest'))!.trigger('click')
    await flushPromises()
    expect(mockGet).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).not.toContain('presetVersionConflict')
  })

  it('WO-UI-27 доп.2: edit prefills from server and saves via update (PUT), no duplicate path', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
    global: { stubs: {} },
      props: {
        open: true, processKey: 'k', targetKind: 'USER_TASK', targetRef: 't1',
        presetId: 'p1',
      },
    })
    mounted.push(w)
    await flushPromises()
    expect((document.querySelector('#preset-name') as HTMLInputElement).value).toBe('base')
    await docBtns().find((b) => b.text().trim() === 'save')!.trigger('click')
    await flushPromises()
    // WO-UI-27 доп.2: пути «сохранить как копию» нет — правка идёт через PUT.
    expect(mockUpdate).toHaveBeenCalled()
    expect(mockCreate).not.toHaveBeenCalled()
  })

  it('delete needs two clicks, then emits deleted', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
    global: { stubs: {} },
      props: { open: true, processKey: 'k', targetKind: 'START', targetRef: null, presetId: 'p1' },
    })
    mounted.push(w)
    await flushPromises()
    const delBtn = docBtns().find((b) => b.text().includes('presetDelete'))!
    await delBtn.trigger('click')
    expect(mockDelete).not.toHaveBeenCalled()
    await docBtns().find((b) => b.text().includes('presetConfirmDelete'))!.trigger('click')
    await flushPromises()
    expect(mockDelete).toHaveBeenCalledWith('p1')
    expect(w.emitted('deleted')).toBeTruthy()
  })

  it('history view loads entries and shows before/after', async () => {
    mockHistory.mockResolvedValue([
      { id: 'h1', action: 'UPDATE', actorUserId: 'u1', at: '2026-01-02',
        variablesBefore: [{ name: 'n', type: 'LONG', value: '1' }],
        variablesAfter: [{ name: 'n', type: 'LONG', value: '2' }] },
    ])
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
    global: { stubs: {} },
      props: { open: true, processKey: 'k', targetKind: 'START', targetRef: null, presetId: 'p1' },
    })
    mounted.push(w)
    await flushPromises()
    await docBtns().find((b) => b.text().includes('presetHistory'))!.trigger('click')
    await flushPromises()
    expect(mockHistory).toHaveBeenCalledWith('p1')
    expect(document.body.textContent).toContain('UPDATE')
  })
})
