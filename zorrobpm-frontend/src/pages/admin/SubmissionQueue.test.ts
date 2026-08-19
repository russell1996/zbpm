// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SubmissionQueue from './SubmissionQueue.vue'

const mockGetPending = vi.hoisted(() => vi.fn())
const mockApprove = vi.hoisted(() => vi.fn().mockResolvedValue({}))
const mockReject = vi.hoisted(() => vi.fn().mockResolvedValue({}))
const mockGetBpmn = vi.hoisted(() => vi.fn())
vi.mock('@/services/submissionService', () => ({
  getPendingSubmissions: mockGetPending,
  approveSubmission: mockApprove,
  rejectSubmission: mockReject,
  getSubmissionBpmn: mockGetBpmn,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))
vi.mock('@/widgets/bpmn/BpmnViewer.vue', () => ({
  default: { template: '<div class="bpmn-stub" />' },
}))

// WO-ACL-10 criteria 12-13: the DTO carries the enriched submitter identity
// (ACL-9) and the raw model is fetched via /process-submissions/{id}/bpmn.
const PENDING = [
  {
    id: 'sub-1',
    processKey: 'p1',
    name: 'Process One',
    status: 'PENDING',
    submittedBy: 'e58f1a42-0000-4000-8000-000000000001',
    submittedByUsername: 'alice',
    submittedByFullName: 'Alice Admin',
    submittedByEmail: 'alice@test.com',
    submittedAt: '2026-08-01T10:00:00Z',
    rejectReason: null,
    previousSubmissionId: null,
  },
]

describe('SubmissionQueue (WO-ACL-6 criterion 6, WO-ACL-10 criteria 12-13)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetPending.mockResolvedValue([...PENDING])
  })

  it('lists pending submissions for the admin', async () => {
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    expect(mockGetPending).toHaveBeenCalledTimes(1)
    const text = wrapper.text()
    expect(text).toContain('p1')
    expect(text).toContain('approve')
    expect(text).toContain('reject')
  })

  it('criterion 12: shows submitter name and email, never the raw UUID', async () => {
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('Alice Admin')
    expect(text).toContain('alice@test.com')
    expect(text).not.toContain('e58f1a42-0000-4000-8000-000000000001')
  })

  it('criterion 13: clicking the ROW opens the submission model via /process-submissions/{id}/bpmn (WO-ACL-11 criterion 6: the row is the control, the view button is gone)', async () => {
    mockGetBpmn.mockResolvedValue('<bpmn:definitions />')
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    // the old "view" button must be gone
    expect(wrapper.text()).not.toContain('view')
    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(mockGetBpmn).toHaveBeenCalledWith('sub-1')
    // modal with the model title and the BpmnViewer (rendered once xml arrives)
    const modal = wrapper.find('.fixed.inset-0')
    expect(modal.exists()).toBe(true)
    expect(modal.text()).toContain('Process One')
    expect(modal.find('.bpmn-stub').exists()).toBe(true)
  })

  it('criterion 13: the model preview closes and unloads the xml', async () => {
    mockGetBpmn.mockResolvedValue('<bpmn:definitions />')
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    const closeBtn = wrapper.findAll('button').find((b) => b.text() === 'close')!
    await closeBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('.fixed.inset-0').exists()).toBe(false)
  })

  it('WO-ACL-11 criterion 9: Enter on the focused row opens the model preview', async () => {
    mockGetBpmn.mockResolvedValue('<bpmn:definitions />')
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(mockGetBpmn).toHaveBeenCalledWith('sub-1')
  })

  it('WO-ACL-11 criterion 8: clicking approve/reject does NOT open the model preview', async () => {
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const approveBtn = wrapper.findAll('button').find((b) => b.text() === 'approve')!
    await approveBtn.trigger('click')
    await flushPromises()
    expect(mockGetBpmn).not.toHaveBeenCalled()
    expect(wrapper.find('.fixed.inset-0').exists()).toBe(false)
    expect(mockApprove).toHaveBeenCalledWith('sub-1')
  })

  it('approving calls approveSubmission and refreshes the list', async () => {
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const approveBtn = wrapper.findAll('button').find((b) => b.text() === 'approve')!
    await approveBtn.trigger('click')
    await flushPromises()
    expect(mockApprove).toHaveBeenCalledWith('sub-1')
    expect(mockGetPending).toHaveBeenCalledTimes(2)
  })

  it('reject requires a reason: button disabled without one, submits it with one', async () => {
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const rejectBtn = wrapper.findAll('button').find((b) => b.text() === 'reject')!
    await rejectBtn.trigger('click')
    await flushPromises()
    // dialog is open; the confirm button inside it is disabled because reason is empty
    const dialog = wrapper.find('.fixed.inset-0')
    expect(dialog.exists()).toBe(true)
    let confirm = dialog.findAll('button').find((b) => b.text() === 'reject' && b.attributes('disabled') !== undefined)
    expect(confirm).toBeTruthy()
    // type a reason
    const textarea = dialog.find('textarea')
    await textarea.setValue('Key collides with an existing process')
    await flushPromises()
    confirm = dialog.findAll('button').find((b) => b.text() === 'reject' && b.attributes('disabled') === undefined)
    expect(confirm).toBeTruthy()
    await confirm!.trigger('click')
    await flushPromises()
    expect(mockReject).toHaveBeenCalledWith('sub-1', 'Key collides with an existing process')
    expect(mockGetPending).toHaveBeenCalledTimes(2)
  })

  it('shows an empty-state when nothing is pending', async () => {
    mockGetPending.mockResolvedValue([])
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    expect(wrapper.text()).toContain('noPendingSubmissions')
  })
})
