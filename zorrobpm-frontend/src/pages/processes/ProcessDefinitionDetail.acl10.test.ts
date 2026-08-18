// @vitest-environment jsdom
/**
 * WO-ACL-10 criteria 7-9:
 *   7 — the "upload new version" dialog closes after a successful submit;
 *   8 — on failure it stays open with the error text;
 *   9 — the active tab keeps its border-primary highlight: the double -mb-px
 *       (nav + button) that pushed the ribbon under the container border is gone.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'

const mockListMembers = vi.hoisted(() => vi.fn())
const mockAddVersion = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'def2', key: 'test-proc', version: 2 }))
vi.mock('@/services/adminService', () => ({
  listMembers: mockListMembers,
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue(null),
  addProcessDefinitionVersion: mockAddVersion,
}))
vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  saveElementSchema: vi.fn(),
  getForm: vi.fn(),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))

const mockAuth = vi.hoisted(() => ({ id: 'u-owner', isSuperAdmin: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: mockAuth.id }, isSuperAdmin: mockAuth.isSuperAdmin }),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'def1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
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
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

const MEMBERS = [
  { userId: 'u-owner', username: 'alice', fullName: 'Alice A.', email: 'alice@test.com', role: 'OWNER', addedBy: null, addedAt: '2026-01-01', processKey: 'test-proc' },
]

async function mountDetail() {
  mockListMembers.mockResolvedValue([...MEMBERS])
  const wrapper = mount(ProcessDefinitionDetail, {
    global: { stubs: { teleport: true }, plugins: [createPinia()] },
  })
  await flushPromises()
  return wrapper
}

function versionButton(wrapper: Awaited<ReturnType<typeof mountDetail>>) {
  return wrapper.findAll('button').find((b) => b.text().includes('uploadNewVersion'))
}

describe('ProcessDefinitionDetail — WO-ACL-10 criteria 7-9', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockAuth.id = 'u-owner'
    mockAuth.isSuperAdmin = false
    mockAddVersion.mockResolvedValue({ id: 'def2', key: 'test-proc', version: 2 })
  })

  it('criterion 7: the version dialog closes after a successful submit', async () => {
    const wrapper = await mountDetail()
    await versionButton(wrapper)!.trigger('click')
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.showVersionModal).toBe(true)
    vm.versionBpmn = '<definitions id="v2" />'
    await vm.$nextTick()
    await vm.submitNewVersion()
    await flushPromises()
    expect(mockAddVersion).toHaveBeenCalledWith('def1', '<definitions id="v2" />')
    expect(vm.showVersionModal).toBe(false)
    // the dialog content (hint text) is gone from the DOM
    expect(wrapper.text()).not.toContain('uploadNewVersionHint')
  })

  it('criterion 8: on failure the dialog stays open and shows the error text', async () => {
    mockAddVersion.mockRejectedValueOnce(new Error('boom-version'))
    const wrapper = await mountDetail()
    await versionButton(wrapper)!.trigger('click')
    await flushPromises()
    const vm = wrapper.vm as any
    vm.versionBpmn = '<definitions id="v2" />'
    await vm.$nextTick()
    await vm.submitNewVersion()
    await flushPromises()
    expect(vm.showVersionModal).toBe(true)
    expect(wrapper.text()).toContain('boom-version')
    expect(mockToast.error).toHaveBeenCalledWith(expect.stringContaining('boom-version'))
  })

  it('criterion 9 POF: the active tab keeps border-primary', async () => {
    const wrapper = await mountDetail()
    const tabs = wrapper.findAll('nav[role="tablist"] button')
    const active = tabs.filter((b) => b.classes().includes('border-primary'))
    // exactly the first tab is active by default
    expect(active.length).toBe(1)
    expect(active[0].text()).toContain('bpmnProcess')
  })

  it('criterion 9: -mb-px lives only on the nav — buttons must not repeat it', async () => {
    const wrapper = await mountDetail()
    const nav = wrapper.find('nav[role="tablist"]')
    expect(nav.classes()).toContain('-mb-px')
    const tabs = wrapper.findAll('nav[role="tablist"] button')
    expect(tabs.length).toBeGreaterThanOrEqual(6)
    for (const b of tabs) {
      expect(b.classes()).not.toContain('-mb-px')
      expect(b.classes()).toContain('border-b-2')
    }
  })

  // WO-ACL-10 criterion 19: processName is provided SYNCHRONOUSLY in setup().
  // The old code called provide() inside the async loadDefinition() — Vue warned
  // "provide() can only be used inside setup()" and the breadcrumb never got it.
  it('criterion 19: provide(processName) happens in setup, not inside the async loader', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await mountDetail()
    expect(warnSpy).not.toHaveBeenCalledWith(expect.stringContaining('provide() can only be used inside setup()'))
    warnSpy.mockRestore()
  })
})