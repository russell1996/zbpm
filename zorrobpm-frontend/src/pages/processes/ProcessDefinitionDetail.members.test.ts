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
  searchMemberCandidates: vi.fn().mockResolvedValue([]),
  addMember: vi.fn().mockResolvedValue({}),
}))

// mutable current-user identity — flips between OWNER and VIEWER across tests
const mockAuthUser = vi.hoisted(() => ({ id: 'u-owner' }))
const mockSuperAdmin = vi.hoisted(() => ({ value: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: mockAuthUser, isSuperAdmin: mockSuperAdmin.value }),
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

async function mountDetail(members: typeof MEMBERS = MEMBERS) {
  mockListMembers.mockResolvedValue([...members])
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
    mockSuperAdmin.value = false
  })

  it('criterion 2: members and roles are listed for any member (roles OWNER/VIEWER visible)', async () => {
    const wrapper = await mountDetail()
    expect(mockListMembers).toHaveBeenCalledWith('test-proc')
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.text()).toContain('OWNER')
    expect(wrapper.text()).toContain('VIEWER')
  })

  it('criterion 3: an OWNER sees role management (role select + remove) in OTHER rows', async () => {
    const wrapper = await mountDetail()
    // own row (alice/u-owner) has NO select (WO-ACL-14 criterion 16), bob's row has one
    const selects = wrapper.findAll('select')
    expect(selects.length).toBe(1)
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

  it('criterion 14 (WO-ACL-10): a non-member sees the member list, noAccess is not shown on this tab', async () => {
    // u-outsider is not among the members — ACL-9 opened the member list to any
    // authenticated user; the noAccess state stays on instances/tasks/variables.
    mockAuthUser.id = 'u-outsider'
    const wrapper = await mountDetail()
    expect(mockListMembers).toHaveBeenCalledWith('test-proc')
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.text()).toContain('alice@test.com')
    expect(wrapper.text()).toContain('bob@test.com')
    expect(wrapper.text()).not.toContain('noAccess')
    expect(wrapper.findAll('select').length).toBe(0)
  })

  it('criterion 14 (WO-ACL-10): even with an empty member list a non-member sees noMembers, never noAccess', async () => {
    mockAuthUser.id = 'u-outsider'
    const wrapper = await mountDetail([])
    expect(wrapper.text()).toContain('noMembers')
    expect(wrapper.text()).not.toContain('noAccess')
  })

  it('criterion 3: OWNER can change a member role via the API', async () => {
    const wrapper = await mountDetail()
    const selects = wrapper.findAll('select')
    // the only select belongs to bob's row (VIEWER) → promote to DESIGNER
    await selects[0].setValue('DESIGNER')
    await flushPromises()
    expect(mockChangeRole).toHaveBeenCalledWith('test-proc', 'u-viewer', 'DESIGNER')
  })

  // ---- WO-INT-4 criterion 6: system accounts are marked in the member list ----

  it('criterion 6: a SYSTEM member is visually marked, a human member is not', async () => {
    const sys = { userId: 'u-sys', username: 'integration-bot', fullName: 'Integration Bot', email: 'bot@test.com', role: 'OWNER', addedBy: 'u-owner', addedAt: '2026-01-03', processKey: 'test-proc', isSystem: true }
    const wrapper = await mountDetail([...MEMBERS, sys])
    // the badge key resolves to visible text in the system row
    const rows = wrapper.findAll('tbody tr')
    const sysRow = rows.find((r) => r.text().includes('integration-bot'))
    expect(sysRow).toBeDefined()
    expect(sysRow!.text()).toContain('systemAccount')
    // human member row carries no badge
    const humanRow = rows.find((r) => r.text().includes('alice'))
    expect(humanRow).toBeDefined()
    expect(humanRow!.text()).not.toContain('systemAccount')
  })

  it('criterion 3: OWNER can remove a member via the API', async () => {
    const wrapper = await mountDetail()
    const removeButtons = wrapper.findAll('button').filter((b) => b.text() === 'remove')
    // bob is not the current user → his remove button is present and enabled
    expect(removeButtons).toHaveLength(1)
    await removeButtons[0].trigger('click')
    await flushPromises()
    expect(mockRemoveMember).toHaveBeenCalledWith('test-proc', 'u-viewer')
  })

  // ---- WO-ACL-14 criteria 14-16: own row has no remove/role controls ----

  it('criterion 14: the OWN row has NO remove button at all (not disabled — absent) and NO role select', async () => {
    const wrapper = await mountDetail()
    // the whole row for alice (u-owner): no remove button, no select inside it
    const rows = wrapper.findAll('tbody tr')
    const ownRow = rows.find((r) => r.text().includes('alice'))
    expect(ownRow).toBeDefined()
    expect(ownRow!.find('button').exists()).toBe(false)
    expect(ownRow!.find('select').exists()).toBe(false)
    // the "you" marker stands in place of the controls
    expect(ownRow!.text()).toContain('you')
  })

  it('criterion 15: remove exists in OTHER rows and works (owner removes another member)', async () => {
    const wrapper = await mountDetail()
    const rows = wrapper.findAll('tbody tr')
    const otherRow = rows.find((r) => r.text().includes('bob'))
    expect(otherRow).toBeDefined()
    const removeInRow = otherRow!.findAll('button').filter((b) => b.text() === 'remove')
    expect(removeInRow).toHaveLength(1)
    await removeInRow[0].trigger('click')
    await flushPromises()
    expect(mockRemoveMember).toHaveBeenCalledWith('test-proc', 'u-viewer')
  })

  it('criterion 16: changing your own role in your own row is impossible (no select for self)', async () => {
    const wrapper = await mountDetail()
    const rows = wrapper.findAll('tbody tr')
    const ownRow = rows.find((r) => r.text().includes('alice'))!
    // no select in the own row → no path to call changeMemberRole for u-owner
    expect(ownRow.findAll('select')).toHaveLength(0)
    const otherRow = rows.find((r) => r.text().includes('bob'))!
    // other rows still carry the select (role changes happen by owner/admin)
    expect(otherRow.findAll('select')).toHaveLength(1)
  })

  // ---- WO-ACL-14 criteria 9 & 13: member adding via dialog ----

  it('criterion 9: the members tab has an "Add member" BUTTON (opens the dialog), no inline search', async () => {
    const wrapper = await mountDetail()
    const addButton = wrapper.findAll('button').find((b) => b.text() === 'addMember')
    expect(addButton).toBeDefined()
    // inline search is gone from the tab: no candidate input, no "search candidates" hint
    expect(wrapper.findAll('input').some((i) => i.attributes('placeholder') === 'searchCandidatePlaceholder')).toBe(false)
    expect(wrapper.text()).not.toContain('searchCandidateHint')
    // clicking opens the dialog — the dialog's search input appears
    await addButton!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('input').some((i) => i.attributes('placeholder') === 'searchCandidatePlaceholder')).toBe(true)
  })

  it('criterion 13: a VIEWER (non-owner, non-admin) sees NO "Add member" button', async () => {
    mockAuthUser.id = 'u-viewer'
    const wrapper = await mountDetail()
    expect(wrapper.findAll('button').some((b) => b.text() === 'addMember')).toBe(false)
  })

  it('criterion 13: a super-admin sees the "Add member" button', async () => {
    // super-admin: not in the members list, but isSuperAdmin grants management
    mockAuthUser.id = 'u-superadmin'
    mockSuperAdmin.value = true
    const wrapper = await mountDetail()
    expect(wrapper.findAll('button').some((b) => b.text() === 'addMember')).toBe(true)
  })
})