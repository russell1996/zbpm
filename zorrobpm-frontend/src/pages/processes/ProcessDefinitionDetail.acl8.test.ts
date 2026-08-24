// @vitest-environment jsdom
/**
 * WO-ACL-8 stage A — visibility criteria 3/4 (Start button by rights), 5 (new
 * version button by DEPLOY), 11 (super-admin manages members without membership).
 *
 * V11 wiring: full component through mount(), real member list from the mocked
 * service (same wiring as the members test), auth identity switched per test.
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

// mutable identity: who am I, and am I a super-admin?
const mockAuth = vi.hoisted(() => ({ id: 'u-viewer', isSuperAdmin: false }))
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

// WO-ACL-11 criteria 20-22: the card's "new version" opens the SHARED deploy
// section BOUND to the current definition (full behavior in its own tests).
vi.mock('@/widgets/processes/ProcessDeploySection.vue', () => ({
  default: {
    name: 'ProcessDeploySectionStub',
    props: ['bound'],
    template: '<div class="deploy-stub"><button class="deploy-done" @click="$emit(\'done\')">done</button></div>',
  },
}))

const MEMBERS = [
  { userId: 'u-owner', username: 'alice', fullName: 'Alice A.', email: 'alice@test.com', role: 'OWNER', addedBy: null, addedAt: '2026-01-01', processKey: 'test-proc' },
  { userId: 'u-viewer', username: 'bob', fullName: 'Bob B.', email: 'bob@test.com', role: 'VIEWER', addedBy: 'u-owner', addedAt: '2026-01-02', processKey: 'test-proc' },
  { userId: 'u-designer', username: 'carol', fullName: 'Carol C.', email: 'carol@test.com', role: 'DESIGNER', addedBy: 'u-owner', addedAt: '2026-01-03', processKey: 'test-proc' },
]

async function mountDetail() {
  mockListMembers.mockResolvedValue([...MEMBERS])
  const wrapper = mount(ProcessDefinitionDetail, {
    global: { stubs: { teleport: true }, plugins: [createPinia()] },
  })
  await flushPromises()
  // click the "Members" tab so member content is visible
  const membersTab = wrapper.findAll('button').find((b) => b.text() === 'members')
  await membersTab?.trigger('click')
  await flushPromises()
  return wrapper
}

type DetailWrapper = Awaited<ReturnType<typeof mountDetail>>

function startButton(wrapper: DetailWrapper) {
  return wrapper.findAll('button').find((b) => b.text().includes('startProcess'))
}

function versionButton(wrapper: DetailWrapper) {
  return wrapper.findAll('button').find((b) => b.text().includes('uploadNewVersion'))
}

describe('ProcessDefinitionDetail — WO-ACL-8 stage A visibility', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockAuth.id = 'u-viewer'
    mockAuth.isSuperAdmin = false
  })

  it('criterion 3: a VIEWER does NOT see the Start button', async () => {
    mockAuth.id = 'u-viewer'
    const wrapper = await mountDetail()
    expect(startButton(wrapper)).toBeUndefined()
  })

  it('criterion 3: a non-member does NOT see the Start button', async () => {
    mockAuth.id = 'u-outsider'
    const wrapper = await mountDetail()
    expect(startButton(wrapper)).toBeUndefined()
  })

  it('criterion 4: an OWNER sees the Start button', async () => {
    mockAuth.id = 'u-owner'
    const wrapper = await mountDetail()
    expect(startButton(wrapper)).toBeDefined()
  })

  it('criterion 4: a DESIGNER sees the Start button', async () => {
    mockAuth.id = 'u-designer'
    const wrapper = await mountDetail()
    expect(startButton(wrapper)).toBeDefined()
  })

  it('criterion 5: a VIEWER does NOT see the "new version" button', async () => {
    mockAuth.id = 'u-viewer'
    const wrapper = await mountDetail()
    expect(versionButton(wrapper)).toBeUndefined()
  })

  it('criterion 5: an OWNER sees the "new version" button and opens the bound deploy section', async () => {
    mockAuth.id = 'u-owner'
    const wrapper = await mountDetail()
    const btn = versionButton(wrapper)
    expect(btn).toBeDefined()
    // open the dialog — it mounts the SHARED deploy section bound to this process
    await btn!.trigger('click')
    await flushPromises()
    const stub = wrapper.findComponent({ name: 'ProcessDeploySectionStub' })
    expect(stub.exists()).toBe(true)
    expect(stub.props('bound')).toEqual({ id: 'def1', key: 'test-proc', name: 'Test' })
  })

  it('criterion 11: a super-admin who is NOT a member sees member management controls', async () => {
    mockAuth.id = 'u-outsider'
    mockAuth.isSuperAdmin = true
    const wrapper = await mountDetail()
    expect(wrapper.findAll('select').length).toBe(3) // one role select per member row
    expect(wrapper.text()).toContain('remove')
  })

  it('criterion 11: a non-super-admin who is NOT a member sees NO management controls', async () => {
    mockAuth.id = 'u-outsider'
    const wrapper = await mountDetail()
    expect(wrapper.findAll('select').length).toBe(0)
    expect(wrapper.text()).not.toContain('remove')
  })

  // WO-UI-3 fix: structure tab now shows noDataYet for everyone (definition data is visible to all authenticated per ADR-8, not gated by membership)
  it('criterion 27: non-member sees noDataYet message in Structure tab (WO-UI-3)', async () => {
    mockAuth.id = 'u-outsider'
    const wrapper = await mountDetail()
    // click Structure tab (label is the i18n key 'bpmnStructure')
    const structureTab = wrapper.findAll('button').find(b => b.text().includes('bpmnStructure'))
    await structureTab?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('noDataYet')
  })

  it('criterion 27: member sees noDataYet message in Structure tab', async () => {
    mockAuth.id = 'u-owner'
    const wrapper = await mountDetail()
    const structureTab = wrapper.findAll('button').find(b => b.text().includes('bpmnStructure'))
    await structureTab?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('noDataYet')
  })
})