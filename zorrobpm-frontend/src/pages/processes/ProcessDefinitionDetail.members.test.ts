// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'

const mockListMembers = vi.hoisted(() => vi.fn())
const mockChangeRole = vi.hoisted(() => vi.fn().mockResolvedValue({}))
const mockRemoveMember = vi.hoisted(() => vi.fn().mockResolvedValue({}))
vi.mock('@/services/adminService', () => ({
  listMembers: mockListMembers,
  changeMemberRole: mockChangeRole,
  removeMember: mockRemoveMember,
}))

// mutable current-user identity — flips between OWNER and VIEWER across tests
const mockAuthUser = vi.hoisted(() => ({ id: 'u-owner' }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: mockAuthUser }),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'def1' } }),
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
  { userId: 'u-viewer', username: 'bob', fullName: 'Bob B.', email: 'bob@test.com', role: 'VIEWER', addedBy: 'u-owner', addedAt: '2026-01-02', processKey: 'test-proc' },
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

describe('ProcessDefinitionDetail members (WO-ACL-6 criteria 2/3)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockAuthUser.id = 'u-owner'
  })

  it('criterion 2: members and roles are listed for any member (roles OWNER/VIEWER visible)', async () => {
    const wrapper = await mountDetail()
    expect(mockListMembers).toHaveBeenCalledWith('test-proc')
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.text()).toContain('OWNER')
    expect(wrapper.text()).toContain('VIEWER')
  })

  it('criterion 3: an OWNER sees role management (role select + remove)', async () => {
    const wrapper = await mountDetail()
    const selects = wrapper.findAll('select')
    expect(selects.length).toBe(2) // one per member row
    expect(wrapper.text()).toContain('remove')
  })

  it('criterion 3: a non-OWNER (VIEWER) sees the list but NO management controls', async () => {
    mockAuthUser.id = 'u-viewer'
    const wrapper = await mountDetail()
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.findAll('select').length).toBe(0)
    expect(wrapper.text()).not.toContain('remove')
  })

  it('criterion 3: OWNER can change a member role via the API', async () => {
    const wrapper = await mountDetail()
    const selects = wrapper.findAll('select')
    // second row = bob (VIEWER) → promote to DESIGNER
    await selects[1].setValue('DESIGNER')
    await flushPromises()
    expect(mockChangeRole).toHaveBeenCalledWith('test-proc', 'u-viewer', 'DESIGNER')
  })

  it('criterion 3: OWNER can remove a member via the API', async () => {
    const wrapper = await mountDetail()
    const removeButtons = wrapper.findAll('button').filter((b) => b.text() === 'remove')
    // bob is not the current user → his remove button is enabled
    await removeButtons[1].trigger('click')
    await flushPromises()
    expect(mockRemoveMember).toHaveBeenCalledWith('test-proc', 'u-viewer')
  })
})