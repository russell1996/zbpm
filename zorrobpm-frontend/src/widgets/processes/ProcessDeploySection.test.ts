// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ProcessDeploySection from './ProcessDeploySection.vue'

// hoisted mutable auth flag — tests flip it to prove the label depends on rights
const mockAuth = vi.hoisted(() => ({ isSuperAdmin: false, username: 'alice' }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin, user: { username: mockAuth.username } }),
}))

const mockDeploy = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'def-1', key: 'p1' }))
const mockSubmit = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'sub-1' }))
const mockAddVersion = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'def-2', key: 'p1' }))
// WO-ACL-10 criterion 4: definitions are looked up by key to decide the mode.
const mockGetDefinitions = vi.hoisted(() => vi.fn().mockResolvedValue({ data: [], totalElements: 0 }))
const mockListMembers = vi.hoisted(() => vi.fn().mockResolvedValue([]))
vi.mock('@/services/processService', () => ({
  deployProcessDefinition: mockDeploy,
  addProcessDefinitionVersion: mockAddVersion,
  getProcessDefinitions: mockGetDefinitions,
}))
vi.mock('@/services/submissionService', () => ({ submitProcessSubmission: mockSubmit }))
vi.mock('@/services/adminService', () => ({ listMembers: mockListMembers }))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

const BPMN = '<bpmn><process id="p1" name="P1" isExecutable="true"/></bpmn>'

async function mountWithBpmn(isAdmin: boolean, xml = BPMN) {
  mockAuth.isSuperAdmin = isAdmin
  const wrapper = mount(ProcessDeploySection)
  const vm = wrapper.vm as any
  vm.bpmnText = xml
  await wrapper.vm.$nextTick()
  // feed through the real input path so parseBpmnMetadata + mode resolution run
  await wrapper.find('textarea').setValue(xml)
  await flushPromises()
  return wrapper
}

describe('ProcessDeploySection (WO-ACL-6 / WO-ACL-10 criteria 3-6)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuth.isSuperAdmin = false
    mockAuth.username = 'alice'
    mockGetDefinitions.mockResolvedValue({ data: [], totalElements: 0 })
    mockListMembers.mockResolvedValue([])
  })

  it('criterion 5 POF: non-admin button is "Submit for Approval" and POSTs a submission, not a deploy', async () => {
    const wrapper = await mountWithBpmn(false)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    expect(mockSubmit).toHaveBeenCalledWith(BPMN)
    expect(mockDeploy).not.toHaveBeenCalled()
  })

  it('criterion 5: SUPER_ADMIN button is "Deploy BPMN" and POSTs a deploy, not a submission', async () => {
    const wrapper = await mountWithBpmn(true)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('deployBpmn'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    expect(mockDeploy).toHaveBeenCalledWith(BPMN)
    expect(mockSubmit).not.toHaveBeenCalled()
  })

  it('criterion 5 POF guard: admin label must NOT leak to a non-admin render', async () => {
    // If the production condition were broken (always admin), this test would fail:
    // a non-admin would see "Deploy BPMN" instead of "Submit for Approval".
    const wrapper = await mountWithBpmn(false)
    expect(wrapper.findAll('button').some((b) => b.text().includes('deployBpmn'))).toBe(false)
    expect(wrapper.findAll('button').some((b) => b.text().includes('submitForApproval'))).toBe(true)
  })

  it('criterion 7: backend validation text is shown, not a generic error', async () => {
    mockSubmit.mockRejectedValueOnce({
      response: { data: { code: 'CONFLICT', message: "Process with key 'p1' already exists — update the model from inside the process" } },
    })
    const wrapper = await mountWithBpmn(false)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    await btn.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain("Process with key 'p1' already exists")
    expect(wrapper.text()).not.toContain('Request failed with status code 400')
    expect(mockToast.error).toHaveBeenCalledWith(expect.stringContaining("already exists"))
  })

  it('criterion 7: generic fallback when the backend sends no message', async () => {
    mockSubmit.mockRejectedValueOnce(new Error('network down'))
    const wrapper = await mountWithBpmn(false)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    await btn.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('network down')
  })

  // --- WO-ACL-10 criteria 4-6: mode resolution from the existing definitions ---

  it('criterion 4 POF: unknown key resolves to "new process will be created"', async () => {
    mockGetDefinitions.mockResolvedValue({ data: [], totalElements: 0 })
    const wrapper = await mountWithBpmn(false)
    expect(wrapper.text()).toContain('willCreateProcess')
    expect(wrapper.text()).toContain('p1')
    expect(mockGetDefinitions).toHaveBeenCalledWith({ processDefinitionKey: 'p1', latestVersionOnly: true })
  })

  it('criterion 4: existing key + OWNER membership resolves to "new version will be added" and POSTs addProcessDefinitionVersion', async () => {
    mockGetDefinitions.mockResolvedValue({
      data: [{ id: 'def-1', key: 'p1', name: 'P1', version: 1, createdAt: '' }],
      totalElements: 1,
    })
    mockListMembers.mockResolvedValue([
      { userId: 'u1', username: 'alice', fullName: 'Alice', email: null, role: 'OWNER', addedBy: null, addedAt: '' },
    ])
    const wrapper = await mountWithBpmn(false)
    expect(wrapper.text()).toContain('willAddVersion')
    expect(wrapper.text()).toContain('P1')
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    await btn.trigger('click')
    await flushPromises()
    expect(mockAddVersion).toHaveBeenCalledWith('def-1', BPMN)
    expect(mockDeploy).not.toHaveBeenCalled()
    expect(mockSubmit).not.toHaveBeenCalled()
  })

  it('criterion 6: existing key without deploy rights shows the owner and blocks submit', async () => {
    mockGetDefinitions.mockResolvedValue({
      data: [{ id: 'def-1', key: 'p1', name: 'P1', version: 1, createdAt: '' }],
      totalElements: 1,
    })
    // alice is not a member — only bob (OWNER) is
    mockListMembers.mockResolvedValue([
      { userId: 'u2', username: 'bob', fullName: 'Bob', email: null, role: 'OWNER', addedBy: null, addedAt: '' },
    ])
    const wrapper = await mountWithBpmn(false)
    expect(wrapper.text()).toContain('noDeployAccess')
    expect(wrapper.text()).toContain('Bob')
    const submitBtn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    expect((submitBtn.element as HTMLButtonElement).disabled).toBe(true)
    await submitBtn.trigger('click')
    await flushPromises()
    expect(mockAddVersion).not.toHaveBeenCalled()
    expect(mockDeploy).not.toHaveBeenCalled()
    expect(mockSubmit).not.toHaveBeenCalled()
  })

  it('criterion 6: DESIGNER membership grants version rights, VIEWER does not', async () => {
    mockGetDefinitions.mockResolvedValue({
      data: [{ id: 'def-1', key: 'p1', name: 'P1', version: 1, createdAt: '' }],
      totalElements: 1,
    })
    mockListMembers.mockResolvedValue([
      { userId: 'u1', username: 'alice', fullName: 'Alice', email: null, role: 'DESIGNER', addedBy: null, addedAt: '' },
      { userId: 'u2', username: 'bob', fullName: 'Bob', email: null, role: 'OWNER', addedBy: null, addedAt: '' },
    ])
    let wrapper = await mountWithBpmn(false)
    expect(wrapper.text()).toContain('willAddVersion')
    mockListMembers.mockResolvedValue([
      { userId: 'u2', username: 'bob', fullName: 'Bob', email: null, role: 'OWNER', addedBy: null, addedAt: '' },
    ])
    wrapper = await mountWithBpmn(false)
    expect(wrapper.text()).toContain('noDeployAccess')
  })

  it('criterion 5: SUPER_ADMIN deploys regardless of membership', async () => {
    mockGetDefinitions.mockResolvedValue({
      data: [{ id: 'def-1', key: 'p1', name: 'P1', version: 1, createdAt: '' }],
      totalElements: 1,
    })
    mockListMembers.mockResolvedValue([])
    const wrapper = await mountWithBpmn(true)
    expect(wrapper.text()).toContain('willAddVersion')
  })
})

// --- WO-ACL-11 criteria 23-26: submit dialog lifecycle ---
describe('ProcessDeploySection submit dialog (WO-ACL-11 criteria 23-26)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuth.isSuperAdmin = false
    mockAuth.username = 'alice'
    mockGetDefinitions.mockResolvedValue({ data: [], totalElements: 0 })
    mockListMembers.mockResolvedValue([])
  })

  it('criterion 23: a successful submission emits done — the parent closes the dialog', async () => {
    const wrapper = await mountWithBpmn(false)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    await btn.trigger('click')
    await flushPromises()
    expect(mockSubmit).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('criterion 24: an error keeps the dialog open and shows the reason', async () => {
    mockSubmit.mockRejectedValueOnce({ response: { data: { message: 'already in review' } } })
    const wrapper = await mountWithBpmn(false)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    await btn.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('done')).toBeUndefined()
    expect(wrapper.text()).toContain('already in review')
  })

  it('criterion 25: the button is disabled from the moment of the click until the response', async () => {
    let resolveSubmit!: (v: unknown) => void
    mockSubmit.mockImplementationOnce(() => new Promise((res) => { resolveSubmit = res }))
    const wrapper = await mountWithBpmn(false)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    await btn.trigger('click')
    await wrapper.vm.$nextTick()
    expect((btn.element as HTMLButtonElement).disabled).toBe(true)
    resolveSubmit({ id: 'sub-1' })
    await flushPromises()
    expect((btn.element as HTMLButtonElement).disabled).toBe(false)
  })

  it('criterion 26 POF: five clicks while the request is in flight produce exactly ONE service call', async () => {
    let resolveSubmit!: (v: unknown) => void
    mockSubmit.mockImplementationOnce(() => new Promise((res) => { resolveSubmit = res }))
    const wrapper = await mountWithBpmn(false)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    for (let i = 0; i < 5; i++) {
      await btn.trigger('click')
    }
    expect(mockSubmit).toHaveBeenCalledTimes(1)
    resolveSubmit({ id: 'sub-1' })
    await flushPromises()
    expect(mockSubmit).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('done')).toHaveLength(1)
  })
})

// --- WO-ACL-11 criteria 21-22: bound mode (opened from a process card) ---
describe('ProcessDeploySection bound mode (WO-ACL-11 criteria 21-22)', () => {
  const BOUND = { id: 'def-1', key: 'p1', name: 'P1' }
  const BOUND_BPMN = '<bpmn><process id="p1" name="P1" isExecutable="true"/></bpmn>'
  const FOREIGN_BPMN = '<bpmn><process id="other" name="Other" isExecutable="true"/></bpmn>'

  beforeEach(() => {
    vi.clearAllMocks()
    mockAuth.isSuperAdmin = false
    mockAuth.username = 'alice'
    mockGetDefinitions.mockResolvedValue({ data: [], totalElements: 0 })
    mockListMembers.mockResolvedValue([])
  })

  async function mountBound(isAdmin: boolean, xml: string, bound = BOUND) {
    mockAuth.isSuperAdmin = isAdmin
    const wrapper = mount(ProcessDeploySection, { props: { bound } })
    const vm = wrapper.vm as any
    vm.bpmnText = xml
    await wrapper.vm.$nextTick()
    // feed through the real input path so parseBpmnMetadata runs (same as mountWithBpmn)
    await wrapper.find('textarea').setValue(xml)
    await flushPromises()
    return wrapper
  }

  it('criterion 21: the target process is shown in the header with its key', async () => {
    const wrapper = await mountBound(false, BOUND_BPMN)
    expect(wrapper.text()).toContain('targetProcess')
    expect(wrapper.text()).toContain('P1')
    expect(wrapper.text()).toContain('p1')
  })

  it('criterion 21: a model with the right key resolves immediately to "new version"', async () => {
    const wrapper = await mountBound(false, BOUND_BPMN)
    expect(wrapper.text()).toContain('willAddVersion')
    // bound mode does NOT hit the directory lookup
    expect(mockGetDefinitions).not.toHaveBeenCalled()
  })

  it('criterion 21: bound submit adds a version of the bound process — even for the super-admin', async () => {
    const wrapper = await mountBound(true, BOUND_BPMN)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('deployBpmn'))!
    await btn.trigger('click')
    await flushPromises()
    // the super-admin MUST NOT deploy a brand-new process here — bound wins
    expect(mockAddVersion).toHaveBeenCalledWith('def-1', BOUND_BPMN)
    expect(mockDeploy).not.toHaveBeenCalled()
    expect(mockSubmit).not.toHaveBeenCalled()
  })

  it('criterion 22 POF: a model with a foreign key is rejected with an explaining text', async () => {
    const wrapper = await mountBound(false, FOREIGN_BPMN)
    expect(wrapper.text()).toContain('boundKeyMismatch')
    expect(wrapper.text()).toContain('other')
    expect(wrapper.text()).toContain('p1')
    // no "new version will be added" — the foreign model must not be accepted
    expect(wrapper.text()).not.toContain('willAddVersion')
  })

  it('criterion 22: submit is blocked while the key mismatch is shown', async () => {
    const wrapper = await mountBound(false, FOREIGN_BPMN)
    const submitBtn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))!
    expect((submitBtn.element as HTMLButtonElement).disabled).toBe(true)
    await submitBtn.trigger('click')
    await flushPromises()
    expect(mockAddVersion).not.toHaveBeenCalled()
    expect(mockDeploy).not.toHaveBeenCalled()
    expect(mockSubmit).not.toHaveBeenCalled()
  })
})