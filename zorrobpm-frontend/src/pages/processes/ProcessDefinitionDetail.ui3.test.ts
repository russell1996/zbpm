// @vitest-environment jsdom
/**
 * WO-UI-3: structure/docs/versions tabs must show `noDataYet` for non-members,
 * not `noAccess`. Runtime tabs (instances/tasks) still show `noAccess` for non-members
 * (ADR-8). This test proves the three definition tabs are fixed.
 *
 * POF: revert any of the three `t('noDataYet')` back to `isMember ? t('noDataYet') : t('noAccess')`
 * and the corresponding criterion goes RED (shows `noAccess` for non-member).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'

const mockListMembers = vi.hoisted(() => vi.fn())
vi.mock('@/services/adminService', () => ({
  listMembers: mockListMembers,
  changeMemberRole: vi.fn().mockResolvedValue({}),
  removeMember: vi.fn().mockResolvedValue({}),
  searchMemberCandidates: vi.fn().mockResolvedValue([]),
  addMember: vi.fn().mockResolvedValue({}),
}))

const mockAuthUser = vi.hoisted(() => ({ id: 'u-stranger', username: 'stranger' }))
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
    // empty structure/docs/versions
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
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

// members: only owner, so stranger is non-member
const MEMBERS = [
  { userId: 'u-owner', username: 'alice', fullName: 'Alice', email: 'a@test.com', role: 'OWNER', addedBy: null, addedAt: '2026-01-01', processKey: 'test-proc' },
]

async function mountForTab(tabName: string) {
  mockListMembers.mockResolvedValue([...MEMBERS])
  const wrapper = mount(ProcessDefinitionDetail, {
    global: { stubs: { teleport: true }, plugins: [createPinia()] },
  })
  await flushPromises()
  // TabsBar renders labels via t(key) -> key, so 'bpmnStructure', 'requirements', 'versions', 'members'
  const tab = wrapper.findAll('button').find((b) => b.text() === tabName)
  if (!tab) throw new Error(`tab ${tabName} not found, available: ${wrapper.findAll('button').map(b => b.text()).join(', ')}`)
  await tab.trigger('click')
  await flushPromises()
  return wrapper
}

describe('WO-UI-3 definition tabs — non-member empty states', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockAuthUser.id = 'u-stranger'
    mockSuperAdmin.value = false
  })

  it('criterion 1: structure tab without data shows noDataYet for non-member, not noAccess', async () => {
    const wrapper = await mountForTab('bpmnStructure')
    expect(wrapper.text()).toContain('noDataYet')
    expect(wrapper.text()).not.toContain('noAccess')
  })

  it('criterion 2: docs tab without data shows noDataYet for non-member, not noAccess', async () => {
    const wrapper = await mountForTab('requirements')
    expect(wrapper.text()).toContain('noDataYet')
    expect(wrapper.text()).not.toContain('noAccess')
  })

  it('criterion 3: versions tab without data shows noDataYet for non-member, not noAccess', async () => {
    const wrapper = await mountForTab('versions')
    expect(wrapper.text()).toContain('noDataYet')
    expect(wrapper.text()).not.toContain('noAccess')
  })

  it('criterion 4: members tab still shows people for non-member (regression guard for already-fixed ACL-9)', async () => {
    // WO-UI-3 says not to touch runtime tabs (instances/tasks) where noAccess is correct for non-members.
    // For the members tab, ACL-9 already fixed it to show people for any authenticated user, not noAccess.
    // This test ensures our definition-tab fix didn't leak into the members tab.
    const wrapper = await mountForTab('members')
    // non-member should see the members list (alice), not noAccess and not noMembers (since there is a member)
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).not.toContain('noAccess')
  })
})
