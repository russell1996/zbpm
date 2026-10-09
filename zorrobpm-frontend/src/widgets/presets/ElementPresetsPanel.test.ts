// @vitest-environment jsdom
/**
 * WO-VT-1 (фронт): ElementPresetsPanel — список/изменить/дублировать/удалить,
 * избранное, скрытие при выключенном флаге.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPresetsPanel from './ElementPresetsPanel.vue'

const mockList = vi.hoisted(() => vi.fn())
const mockDelete = vi.hoisted(() => vi.fn())
const mockFavorite = vi.hoisted(() => vi.fn())
vi.mock('@/services/presetService', () => ({
  listPresets: mockList,
  deletePreset: mockDelete,
  importPreset: vi.fn(),
  exportPreset: vi.fn(),
  setPresetFavorite: mockFavorite,
  isPresetsDisabled: (e: unknown) => (e as { disabled?: boolean })?.disabled === true,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

const PRESET = {
  id: 'p1',
  processDefinitionKey: 'k',
  targetKind: 'USER_TASK',
  targetRef: 't1',
  name: 'case-a',
  description: null,
  variables: [{ name: 'n', type: 'LONG', value: '1' }],
  ownerUserId: 'u1',
  visibility: 'PRIVATE',
  favorite: false,
  createdAt: '2026-01-01',
  updatedAt: '2026-01-01',
  version: 0,
}

function render() {
  return mount(ElementPresetsPanel, {
    props: { processKey: 'k', targetKind: 'USER_TASK', targetRef: 't1' },
  })
}

describe('ElementPresetsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockList.mockResolvedValue([])
    mockDelete.mockResolvedValue(undefined)
    mockFavorite.mockResolvedValue(undefined)
  })

  it('empty place shows the add-template button', async () => {
    const w = render()
    await flushPromises()
    expect(w.text()).toContain('presetNoTemplates')
    expect(w.text()).toContain('presetAddTemplate')
    expect(mockList).toHaveBeenCalledWith({ key: 'k', kind: 'USER_TASK', ref: 't1' })
  })

  it('WO-UI-27: cards expose edit/export/delete, no duplicate item', async () => {
    mockList.mockResolvedValue([{ ...PRESET }])
    const w = render()
    await flushPromises()
    expect(w.text()).toContain('case-a')
    // WO-VT-3: действия уехали под ⋯ — в закрытом виде их нет.
    expect(w.text()).not.toContain('presetEdit')
    await w.find('[data-testid="preset-card"] [data-testid="row-menu-button"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('presetEdit')
    // WO-UI-27 доп.2: «Дублировать» убран совсем.
    expect(w.text()).not.toContain('presetDuplicate')
    expect(w.text()).toContain('presetExport')
    expect(w.text()).toContain('presetCreateNew')
  })

  it('WO-UI-27: delete via ⋯ menu is one click, quiet undo toast', async () => {
    mockList.mockResolvedValue([{ ...PRESET }])
    const w = render()
    await flushPromises()
    await w.find('[data-testid="preset-card"] [data-testid="row-menu-button"]').trigger('click')
    await flushPromises()
    const delItem = w.find('[data-testid="row-menu-item-delete"]')
    await delItem.trigger('click')
    await flushPromises()
    // Без confirm: сервис вызван сразу, тост тихий (не красный confirm).
    expect(mockDelete).toHaveBeenCalledWith('p1')
    expect(mockToast.success).toHaveBeenCalled()
    expect(w.text()).not.toContain('presetDeleteRowConfirm')
  })

  it('favorite toggle calls the service and flips the star', async () => {
    mockList.mockResolvedValue([{ ...PRESET }])
    const w = render()
    await flushPromises()
    const star = w.find('button[aria-label="presetFavorite"]')
    expect(star.text()).toBe('☆')
    await star.trigger('click')
    await flushPromises()
    expect(mockFavorite).toHaveBeenCalledWith('p1', true)
    expect(star.text()).toBe('★')
  })

  it('oversized import file is rejected client-side (no service call)', async () => {
    const { importPreset } = await import('@/services/presetService')
    mockList.mockResolvedValue([])
    const w = render()
    await flushPromises()
    const big = new File(['x'.repeat(10)], 'big.json', { type: 'application/json' })
    Object.defineProperty(big, 'size', { value: 2 * 1024 * 1024 })
    const input = w.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [big] })
    await input.trigger('change')
    await flushPromises()
    expect(vi.mocked(importPreset)).not.toHaveBeenCalled()
  })

  it('disabled flag hides the whole panel', async () => {
    mockList.mockRejectedValue({ disabled: true })
    const w = render()
    await flushPromises()
    expect(w.text()).toBe('')
  })
})
