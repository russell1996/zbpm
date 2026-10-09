// @vitest-environment jsdom
/**
 * WO-VT-3 раунд 2 (решение CTO по E-VT3-1): вкладки «Шаблоны» на инстансе
 * больше нет — сообщение уходит в модалку из меню «Действия ▾» в шапке,
 * снимок живёт на вкладке «Переменные», менеджер — на странице определения.
 * Старый ?tab=presets редиректит на ?tab=variables.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'
import InstanceMessagePanel from '@/widgets/presets/InstanceMessagePanel.vue'
import InstanceSnapshotPanel from '@/widgets/presets/InstanceSnapshotPanel.vue'
import PresetManagerPanel from '@/widgets/presets/PresetManagerPanel.vue'
import TabsBar from '@/widgets/shared/TabsBar.vue'

const routeHolder = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))
const mockReplace = vi.hoisted(() => vi.fn())
const mockPush = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' }, query: routeHolder.query }),
  useRouter: () => ({ push: mockPush, replace: mockReplace }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/taskService', () => ({
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, cancelled: false, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

vi.mock('@/services/presetService', () => ({
  listPresets: vi.fn().mockResolvedValue([]),
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

vi.mock('@/services/messagePublishService', () => ({
  publishMessage: vi.fn().mockResolvedValue({}),
}))

const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

const stubs = { BpmnViewer: { template: '<div class="bpmn-stub" />' } }

function render() {
  // WO-UI-27: mount БЕЗ своего createPinia — иначе компонент видит ДРУГОЙ
  // стор (PINIA_SAME=false) и вечно показывает loading: init пишет в чужой
  // стор, шаблон читает пустой. Активная pinia — из beforeEach выше.
  // attachTo — чтобы порталы DropdownMenu/Dialog жили в document.
  const host = document.createElement('div')
  document.body.appendChild(host)
  return mount(ProcessInstanceDetail, {
    attachTo: host,
    global: { stubs },
  })
}

describe('ProcessInstanceDetail — WO-VT-3 round 2 IA (E-VT3-1)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    routeHolder.query = {}
  })

  it('presets tab is gone; message/snapshot/manager are NOT mounted inline', async () => {
    const w = render()
    await flushPromises()
    const tabs = w.findComponent(TabsBar).props('tabs') as Array<{ id: string }>
    expect(tabs.map((t) => t.id)).not.toContain('presets')
    expect(w.findComponent(InstanceMessagePanel).exists()).toBe(false)
    expect(w.findComponent(InstanceSnapshotPanel).exists()).toBe(false)
    expect(w.findComponent(PresetManagerPanel).exists()).toBe(false)
  })

  it('Actions menu opens the send-message modal; Esc closes it', async () => {
    const w = render()
    await flushPromises()
    const menu = w.find('[data-testid="instance-actions-menu"] [data-testid="row-menu-button"]')
    ;(menu.element as HTMLElement).click()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    ;(document.querySelector('[data-testid="row-menu-item-send"]') as HTMLElement).click()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    expect(w.find('[data-testid="send-message-modal"]').exists()).toBe(true)
    expect(w.findComponent(InstanceMessagePanel).exists()).toBe(true)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(w.find('[data-testid="send-message-modal"]').exists()).toBe(false)
  })

  it('legacy ?tab=presets redirects to ?tab=variables without errors', async () => {
    // Остальные параметры адреса (?element/?plane/?page) при редиректе
    // НЕ теряются (явное требование CTO по пересадке).
    routeHolder.query = { tab: 'presets', element: 'taskA', plane: 'sub1', page: '2' }
    const w = render()
    await flushPromises()
    expect(mockReplace).toHaveBeenCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ tab: 'variables', element: 'taskA', plane: 'sub1', page: '2' }),
      }),
    )
    expect((w.vm as unknown as { activeTab: string }).activeTab).toBe('variables')
  })

  it('variables tab hosts the snapshot panel bound to this instance', async () => {
    // WO-UI-25: таб живёт в адресе (композабл) — переключаем через query,
    // а не прямым присвоением activeTab (его больше нет как локального ref).
    routeHolder.query = { tab: 'variables' }
    const w = render()
    await flushPromises()
    const panel = w.findComponent(InstanceSnapshotPanel)
    expect(panel.exists()).toBe(true)
    expect(panel.props('processDefinitionId')).toBe('pd-1')
  })

  it('menu item "process templates" navigates to the definition page', async () => {
    const w = render()
    await flushPromises()
    const menu2 = w.find('[data-testid="instance-actions-menu"] [data-testid="row-menu-button"]')
    ;(menu2.element as HTMLElement).click()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    ;(document.querySelector('[data-testid="row-menu-item-templates"]') as HTMLElement).click()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    expect(mockPush).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'process-definition-detail', params: { id: 'pd-1' } }),
    )
  })
})
