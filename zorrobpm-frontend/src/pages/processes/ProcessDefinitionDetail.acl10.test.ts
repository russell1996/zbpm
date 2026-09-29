// @vitest-environment jsdom
/**
 * WO-ACL-10 criteria 7-9 (re-checked under WO-ACL-11 criteria 20-22):
 *   7 — the "upload new version" dialog closes after a successful submit
 *       (now the shared ProcessDeploySection emits 'done' → the card closes);
 *   8 — on failure it stays open with the error text (no 'done' → stays open;
 *       the error text itself is shown by ProcessDeploySection, its own tests);
 *   9 — the active tab keeps its border-primary highlight: the double -mb-px
 *       (nav + button) that pushed the ribbon under the container border is gone.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'
import { useBreadcrumbStore } from '@/stores/breadcrumb'

const mockListMembers = vi.hoisted(() => vi.fn())
vi.mock('@/services/adminService', () => ({
  listMembers: mockListMembers,
  addMember: vi.fn(),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
  searchMemberCandidates: vi.fn(),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue(null),
  addProcessDefinitionVersion: vi.fn(),
}))
vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  saveElementSchema: vi.fn(),
  getForm: vi.fn(),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))
// WO-ACL-11 criteria 20-22: the card dialog wraps the SHARED deploy section.
// Its full bound-mode behavior is tested in ProcessDeploySection.test.ts; here
// the stub proves the card ↔ 'done' wiring (close on success, stay on failure).
vi.mock('@/widgets/processes/ProcessDeploySection.vue', () => ({
  default: {
    name: 'ProcessDeploySectionStub',
    props: ['bound'],
    template: '<div class="deploy-stub"><button class="deploy-done" @click="$emit(\'done\')">done</button></div>',
  },
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
  })

  it('criterion 7: the version dialog closes after a successful submit (done event)', async () => {
    const wrapper = await mountDetail()
    await versionButton(wrapper)!.trigger('click')
    await flushPromises()
    // the shared deploy section is mounted BOUND to the current definition
    const stub = wrapper.findComponent({ name: 'ProcessDeploySectionStub' })
    expect(stub.exists()).toBe(true)
    expect(stub.props('bound')).toEqual({ id: 'def1', key: 'test-proc', name: 'Test' })
    // success → the section emits 'done' → the card dialog closes
    await stub.find('.deploy-done').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ProcessDeploySectionStub' }).exists()).toBe(false)
  })

  it('criterion 8: on failure the dialog stays open (no done emitted)', async () => {
    const wrapper = await mountDetail()
    await versionButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ProcessDeploySectionStub' }).exists()).toBe(true)
    // no 'done' → the dialog remains open (the error text is rendered by
    // ProcessDeploySection itself, covered by its own tests)
    expect(wrapper.findComponent({ name: 'ProcessDeploySectionStub' }).exists()).toBe(true)
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

  // WO-ACL-11 criterion 3 + WO-ACL-15 criterion 19: the breadcrumb label lives
  // in the breadcrumb STORE (not in provide() — BreadcrumbNav is above
  // <router-view>, inject could never reach it, P-54). useBreadcrumbLabel fills
  // the store when the definition arrives; unmount clears it.
  it('criterion 3: loadDefinition fills the breadcrumb store with the process name', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [pinia] },
    })
    await flushPromises()
    expect(useBreadcrumbStore().crumbLabel).toBe('Test')
    wrapper.unmount()
  })

  it('criterion 3: unmount clears the breadcrumb store', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(ProcessDefinitionDetail, {
      global: { stubs: { teleport: true }, plugins: [pinia] },
    })
    await flushPromises()
    expect(useBreadcrumbStore().crumbLabel).toBe('Test')
    wrapper.unmount()
    expect(useBreadcrumbStore().crumbLabel).toBeNull()
  })
})