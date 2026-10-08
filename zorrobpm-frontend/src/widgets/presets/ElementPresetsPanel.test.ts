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

  it('existing presets render rows with edit/duplicate/delete', async () => {
    mockList.mockResolvedValue([{ ...PRESET }])
    const w = render()
    await flushPromises()
    expect(w.text()).toContain('case-a')
    expect(w.text()).toContain('presetEdit')
    expect(w.text()).toContain('presetDuplicate')
    expect(w.text()).toContain('presetCreateNew')
  })

  it('delete needs two clicks, then calls the service', async () => {
    mockList.mockResolvedValue([{ ...PRESET }])
    const w = render()
    await flushPromises()
    const delBtn = w.findAll('button').find((b) => b.text().includes('presetDelete'))!
    await delBtn.trigger('click')
    expect(mockDelete).not.toHaveBeenCalled()
    expect(w.text()).toContain('presetConfirmDelete')
    await w.findAll('button').find((b) => b.text().includes('presetConfirmDelete'))!.trigger('click')
    await flushPromises()
    expect(mockDelete).toHaveBeenCalledWith('p1')
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

  it('disabled flag hides the whole panel', async () => {
    mockList.mockRejectedValue({ disabled: true })
    const w = render()
    await flushPromises()
    expect(w.text()).toBe('')
  })
})
