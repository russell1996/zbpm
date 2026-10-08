// @vitest-environment jsdom
/**
 * WO-VT-3 раунд 2 (решение CTO по E-VT3-1, п.В1+п.4): менеджер шаблонов живёт
 * на странице ОПРЕДЕЛЕНИЯ (своя вкладка), колонка «Элемент» показывает ИМЯ
 * элемента из схемы, неизвестный ref — id как fallback.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'
import PresetManagerPanel from '@/widgets/presets/PresetManagerPanel.vue'

const mockList = vi.hoisted(() => vi.fn())

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

vi.mock('@/services/presetService', () => ({
  listPresets: mockList,
  getPreset: vi.fn(),
  createPreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn().mockResolvedValue(undefined),
  importPreset: vi.fn(),
  exportPreset: vi.fn(),
  getPresetHistory: vi.fn().mockResolvedValue([]),
  setPresetFavorite: vi.fn(),
  changePresetVisibility: vi.fn(),
  isPresetsDisabled: () => false,
  isPresetConflict: () => false,
  presetErrorCode: () => null,
}))

const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: {
      id: 'def1', key: 'test-proc', version: 1, name: 'Test', documentation: null,
      nodes: [
        { id: 'taskA', type: 'userTask', name: 'Согласование заявки', properties: {} },
        { id: 'taskB', type: 'serviceTask', name: '', properties: {} },
      ],
      flows: [],
    },
    currentVersions: [],
    loading: false,
    error: null,
    fetchDefinition: vi.fn(),
    fetchStructure: vi.fn(),
    fetchVersions: vi.fn(),
    startInstance: vi.fn().mockResolvedValue({ id: 'inst-1' }),
  }),
}))

function preset(id: string, targetKind: string, targetRef: string | null) {
  return {
    id, processDefinitionKey: 'test-proc', targetKind, targetRef,
    name: `p-${id}`, description: null, variables: [],
    ownerUserId: 'u1', visibility: 'PRIVATE', favorite: false,
    createdAt: '2026-01-01', updatedAt: '2026-01-01', version: 0,
  }
}

describe('ProcessDefinitionDetail — WO-VT-3 round 2 IA (E-VT3-1 В1)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockList.mockResolvedValue([
      preset('p1', 'USER_TASK', 'taskA'),
      preset('p2', 'SERVICE_TASK', 'ghostRef'),
    ])
  })

  it('presets tab renders the manager bound to this process key', async () => {
    const w = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await flushPromises()
    ;(w.vm as unknown as { activeTab: string }).activeTab = 'presets'
    await flushPromises()
    const manager = w.findComponent(PresetManagerPanel)
    expect(manager.exists()).toBe(true)
    expect(manager.props('processKey')).toBe('test-proc')
  })

  it('element column resolves schema names, unknown ref falls back to id', async () => {
    const w = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await flushPromises()
    ;(w.vm as unknown as { activeTab: string }).activeTab = 'presets'
    await flushPromises()
    const manager = w.findComponent(PresetManagerPanel)
    expect(manager.props('elementNames')).toEqual({ taskA: 'Согласование заявки', taskB: 'taskB' })
    expect(w.text()).toContain('Согласование заявки')
    expect(w.text()).toContain('ghostRef')
  })
})
