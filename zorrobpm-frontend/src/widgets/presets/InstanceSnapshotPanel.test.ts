// @vitest-environment jsdom
/**
 * WO-VT-3 раунд 2 (решение CTO по E-VT3-1, п.Б): после сохранения снимка —
 * тост со ссылкой «Открыть шаблон» на менеджер страницы определения.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import InstanceSnapshotPanel from './InstanceSnapshotPanel.vue'

const mockCreate = vi.hoisted(() => vi.fn())
const mockPush = vi.hoisted(() => vi.fn())

vi.mock('@/services/presetService', () => ({
  listPresets: vi.fn().mockResolvedValue([]),
  getPreset: vi.fn(),
  createPreset: mockCreate,
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

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {}, path: '/' }),
  useRouter: () => ({ push: mockPush }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string, p?: unknown) => k }),
}))

const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

describe('InstanceSnapshotPanel — WO-VT-3 round 2 (E-VT3-1 Б)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreate.mockResolvedValue({ id: 'p-new' })
  })

  it('saved snapshot toasts an "open template" action navigating to the definition', async () => {
    const w = mount(InstanceSnapshotPanel, {
      props: {
        processKey: 'test-proc',
        processDefinitionId: 'def1',
        instanceVariables: [{ name: 'a', type: 'LONG', value: '1' }],
      },
    })
    await w.findAll('button')[0].trigger('click')
    await flushPromises()
    const snapName = document.querySelector('#snap-name') as HTMLInputElement
    expect(snapName, 'snapshot name renders in portal').not.toBeNull()
    snapName.value = 'snap-1'
    snapName.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const saveBtn = [...document.querySelectorAll('button')].find((b) => (b.textContent ?? '').trim() === 'save') as HTMLElement
    expect(saveBtn, 'save renders in portal').not.toBeNull()
    saveBtn.click()
    await flushPromises()
    await flushPromises()
    expect(mockCreate).toHaveBeenCalled()
    expect(mockToast.success).toHaveBeenCalledWith(
      'presetSaved',
      expect.objectContaining({ action: expect.objectContaining({ label: 'presetOpenTemplate' }) }),
    )
    const onClick = mockToast.success.mock.calls[0][1].action.onClick as () => void
    onClick()
    expect(mockPush).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'process-definition-detail', params: { id: 'def1' } }),
    )
  })

  it('WO-UI-27 BUG-2: Ctrl+Enter inside the snapshot dialog saves (keydown reaches content)', async () => {
    const w = mount(InstanceSnapshotPanel, {
      props: {
        processKey: 'test-proc',
        processDefinitionId: 'def1',
        instanceVariables: [{ name: 'a', type: 'LONG', value: '1' }],
      },
    })
    await w.findAll('button')[0].trigger('click')
    await flushPromises()
    const snapName = document.querySelector('#snap-name') as HTMLInputElement
    snapName.value = 'snap-1'
    snapName.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    snapName.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }))
    await flushPromises()
    expect(mockCreate).toHaveBeenCalled()
    w.unmount()
  })
})
