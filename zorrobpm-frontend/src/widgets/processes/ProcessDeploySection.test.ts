// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProcessDeploySection from './ProcessDeploySection.vue'

// hoisted mutable auth flag — tests flip it to prove the label depends on rights
const mockAuth = vi.hoisted(() => ({ isSuperAdmin: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin }),
}))

const mockDeploy = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'def-1', key: 'p1' }))
const mockSubmit = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'sub-1' }))
vi.mock('@/services/processService', () => ({ deployProcessDefinition: mockDeploy }))
vi.mock('@/services/submissionService', () => ({ submitProcessSubmission: mockSubmit }))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

async function mountWithBpmn(isAdmin: boolean) {
  mockAuth.isSuperAdmin = isAdmin
  const wrapper = mount(ProcessDeploySection)
  const vm = wrapper.vm as any
  vm.bpmnText = '<bpmn><process id="p1" name="P1" isExecutable="true"/></bpmn>'
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('ProcessDeploySection (WO-ACL-6)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuth.isSuperAdmin = false
  })

  it('criterion 5 POF: non-admin button is "Submit for Approval" and POSTs a submission, not a deploy', async () => {
    const wrapper = await mountWithBpmn(false)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('submitForApproval'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    expect(mockSubmit).toHaveBeenCalledWith(wrapper.vm.$el ? '<bpmn><process id="p1" name="P1" isExecutable="true"/></bpmn>' : expect.anything())
    expect(mockDeploy).not.toHaveBeenCalled()
  })

  it('criterion 5: SUPER_ADMIN button is "Deploy BPMN" and POSTs a deploy, not a submission', async () => {
    const wrapper = await mountWithBpmn(true)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('deployBpmn'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    expect(mockDeploy).toHaveBeenCalledWith('<bpmn><process id="p1" name="P1" isExecutable="true"/></bpmn>')
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
})
