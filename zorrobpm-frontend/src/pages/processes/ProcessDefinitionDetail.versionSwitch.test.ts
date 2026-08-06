// @vitest-environment jsdom
/**
 * WO-FE-20: Vue Router component-reuse — clicking another version in the table
 * changes route.params.id but does NOT re-create the component, so onMounted
 * doesn't fire again and the page shows stale data from the first-opened version.
 *
 * Fix: watch(() => route.params.id, ...) triggers loadDefinition().
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'

// ── Reactive route params — emulates Vue Router reusing the component ──
const routeParams = reactive<Record<string, string | string[]>>({ id: 'v1-id' })
const pushFn = vi.fn()

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: routeParams,
    path: '/processes/definitions/v1-id',
    fullPath: '/processes/definitions/v1-id',
    name: 'process-definition-detail',
  }),
  useRouter: () => ({ push: pushFn }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

// ── Test data: two versions of the same process key ──
const DEFINITIONS: Record<string, any> = {
  'v1-id': { id: 'v1-id', key: 'test-proc', version: 1, name: 'Version One', sha256: 'aaa', createdAt: '2026-01-01', startFormKey: null },
  'v2-id': { id: 'v2-id', key: 'test-proc', version: 2, name: 'Version Two', sha256: 'bbb', createdAt: '2026-02-01', startFormKey: null },
}
const STRUCTURES: Record<string, any> = {
  'v1-id': { id: 'v1-id', key: 'test-proc', version: 1, name: 'Version One', documentation: null, nodes: [], flows: [] },
  'v2-id': { id: 'v2-id', key: 'test-proc', version: 2, name: 'Version Two', documentation: null, nodes: [], flows: [] },
}

// ── Mock store (reactive) ──
const fetchDefinition = vi.fn(async (id: string) => {
  storeState.currentDefinition = DEFINITIONS[id] ?? null
})
const fetchStructure = vi.fn(async (id: string) => {
  storeState.currentStructure = STRUCTURES[id] ?? null
})
const fetchVersions = vi.fn(async () => {
  storeState.currentVersions = [DEFINITIONS['v2-id'], DEFINITIONS['v1-id']]
})

const storeState = reactive({
  currentDefinition: null as any,
  currentStructure: null as any,
  currentVersions: [] as any[],
  loading: false,
  error: null as string | null,
  fetchDefinition,
  fetchStructure,
  fetchVersions,
  startInstance: vi.fn(),
})

vi.mock('@/stores/process', () => ({
  useProcessStore: () => storeState,
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn(async (id: string) => `<definitions id="${id}" />`),
  getProcessDefinitionStructure: vi.fn(async (id: string) => ({ id, key: 'test-proc', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] })),
}))

vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  saveElementSchema: vi.fn(),
  getForm: vi.fn(),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))

describe('WO-FE-20: version switch reloads data (component-reuse fix)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    routeParams.id = 'v1-id'
    storeState.currentDefinition = null
    storeState.currentStructure = null
    storeState.currentVersions = []
    storeState.loading = false
    storeState.error = null
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 1: switching version updates h1 + bpmnXml + versions
  // ─────────────────────────────────────────────────────────────
  it('CRIT-1 GREEN: changing route.params.id reloads data for the new version', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await flushPromises()

    // v1 loaded via onMounted
    expect(fetchDefinition).toHaveBeenCalledWith('v1-id')
    expect(storeState.currentDefinition?.name).toBe('Version One')
    expect(wrapper.text()).toContain('Version One')
    const vm = wrapper.vm as any
    expect(vm.bpmnXml).toContain('v1-id')

    fetchDefinition.mockClear()

    // ── Emulate clicking another version: route.params.id changes,
    //    but the component instance is NOT re-created (Vue Router reuse) ──
    routeParams.id = 'v2-id'
    await flushPromises()

    // After fix: watch detects param change → loadDefinition('v2-id')
    expect(fetchDefinition).toHaveBeenCalledWith('v2-id')
    expect(storeState.currentDefinition?.name).toBe('Version Two')
    expect(wrapper.text()).toContain('Version Two')
    expect(vm.bpmnXml).toContain('v2-id')
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 2: direct URL navigation (F5) — onMounted path works
  // ─────────────────────────────────────────────────────────────
  it('CRIT-2 GREEN: direct navigation to a specific version loads its data via onMounted', async () => {
    routeParams.id = 'v2-id'
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await flushPromises()

    expect(fetchDefinition).toHaveBeenCalledWith('v2-id')
    expect(storeState.currentDefinition?.name).toBe('Version Two')
    expect(wrapper.text()).toContain('Version Two')
    const vm = wrapper.vm as any
    expect(vm.bpmnXml).toContain('v2-id')
  })

  // ─────────────────────────────────────────────────────────────
  // Regression: onMounted still fires on first render
  // ─────────────────────────────────────────────────────────────
  it('CRIT-2 REGRESSION: onMounted loads initial version on first render', async () => {
    routeParams.id = 'v1-id'
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await flushPromises()

    expect(fetchDefinition).toHaveBeenCalledWith('v1-id')
    expect(storeState.currentDefinition?.name).toBe('Version One')
    expect(wrapper.text()).toContain('Version One')
  })

  // ─────────────────────────────────────────────────────────────
  // bpmnXml is reset on version switch (no stale XML at error boundary)
  // ─────────────────────────────────────────────────────────────
  it('bpmnXml is reset before loading new version data', async () => {
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [createPinia()] },
    })
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.bpmnXml).toContain('v1-id')

    // Switch to v2 — bpmnXml should be overwritten with v2 XML
    routeParams.id = 'v2-id'
    await flushPromises()
    expect(vm.bpmnXml).toContain('v2-id')
    expect(vm.bpmnXml).not.toContain('v1-id')
  })
})
