// @vitest-environment jsdom
/**
 * WO-UI-42 п.1 (UI-33 О-1): деструктивный акцент кнопки «Удалить» в диалоге
 * редактора шаблона не был закреплён ни одним тестом — регресс к нейтральному
 * виду прошёл бы молча (мутация M3 в приёмке UI-33 «выжила»: 7/7 браузерных
 * и 12/12 unit остались GREEN).
 *
 * Требование WO-UI-33 п.2: «Удалить» — outline с деструктивным акцентом
 * (красный текст/граница/подсветка при наведении). Закрепляем это ТОЧНЫМИ
 * классами: слияние tailwind (cn/tw-merge) выкидывает нейтральные
 * outline-классы, поэтому проверка «есть деструктивные токены И нет
 * нейтральных» — однозначна.
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

/** Кнопки диалога живут в портале (document.body), не внутри wrapper. */
function docBtn(text: string): HTMLButtonElement {
  const el = [...document.querySelectorAll('button')].find(
    (b) => (b.textContent ?? '').trim() === text,
  )
  expect(el, `кнопка «${text}» отрисована`).toBeTruthy()
  return el as HTMLButtonElement
}

describe('WO-UI-42 п.1 (UI-33 О-1): деструктивный акцент кнопки «Удалить»', () => {
  afterEach(() => {
    for (const w of mounted.splice(0)) w.unmount()
    document.body.innerHTML = ''
  })
  beforeEach(() => {
    vi.clearAllMocks()
    mockGet.mockResolvedValue({ ...PRESET })
  })

  async function mountEditDialog(): Promise<void> {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const w = mount(PresetEditorDialog, {
      attachTo: host,
      global: { stubs: {} },
      props: { open: true, processKey: 'k', targetKind: 'START', targetRef: null, presetId: 'p1' },
    })
    mounted.push(w)
    await flushPromises()
  }

  it('«Удалить» несёт деструктивные классы (text/border/hover) и не нейтральные outline-классы', async () => {
    await mountEditDialog()
    const btn = docBtn('presetDelete')
    const cls = btn.className
    // Деструктивный акцент обязателен (WO-UI-33 п.2).
    expect(cls, 'красный текст на кнопке «Удалить»').toContain('text-destructive')
    expect(cls, 'красная граница на кнопке «Удалить»').toContain('border-destructive/40')
    expect(cls, 'красная подсветка при наведении').toContain('hover:bg-destructive/10')
    expect(cls, 'красный текст при наведении').toContain('hover:text-destructive')
    // Нейтральный outline-вид (подмена деструктивного акцента) — не остаётся:
    // tw-merge отбрасывает ПРОИГРАННЫЕ классы, значит их отсутствие — признак
    // именно деструктивного варианта, а не «просто не задали».
    expect(cls, 'нейтральная граница outline убрана').not.toContain('border-input')
    expect(cls, 'нейтральная подсветка outline убрана').not.toContain('hover:bg-accent')
    expect(cls, 'нейтральный текст outline убран').not.toContain('hover:text-accent-foreground')
  })

  it('«Удалить» и «Сохранить» — разные по виду: у сохранения нет деструктивных токенов', async () => {
    await mountEditDialog()
    const save = docBtn('save')
    expect(save.className).not.toContain('text-destructive')
    expect(save.className).not.toContain('border-destructive/40')
    // Шаблон кнопки остаётся shadcn (не «голый» <button> без variant-классов).
    expect(save.className).toContain('inline-flex')
  })

  it('подтверждение удаления той же кнопкой («Точно удалить?») — деструктивный акцент не теряется', async () => {
    await mountEditDialog()
    const del = docBtn('presetDelete')
    del.click()
    await flushPromises()
    const confirm = docBtn('presetConfirmDelete')
    expect(confirm.className).toContain('text-destructive')
    expect(confirm.className).toContain('border-destructive/40')
  })
})
