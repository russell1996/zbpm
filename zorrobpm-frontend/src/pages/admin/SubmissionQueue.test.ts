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
// Known error-code keys (the only ones translated with params in this suite) —
// an UNKNOWN key must return the key itself, exactly like vue-i18n does.
const KNOWN_ERROR_KEYS = new Set(['errors.PENDING_SUBMISSION_EXISTS', 'errors.SUBMISSION_ALREADY_REVIEWED'])
vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (k: string, params?: Record<string, unknown>) =>
      params && Object.keys(params).length && KNOWN_ERROR_KEYS.has(k) ? `${k}:${Object.values(params).join('|')}` : k,
    locale: { value: 'en' },
  }),
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

// WO-ACL-15 criteria 9-12: history queue (status tabs), decision column,
// model preview from any status, translated error codes.
describe('SubmissionQueue (WO-ACL-15 criteria 9-12)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetPending.mockResolvedValue([...PENDING])
  })

  it('criterion 9: status tabs exist, PENDING is the default filter and is passed to the API', async () => {
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    expect(mockGetPending).toHaveBeenCalledWith('PENDING')
    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs.map((b) => b.text())).toEqual([
      'submissionStatusPending',
      'submissionStatusApproved',
      'submissionStatusRejected',
      'submissionStatusAll',
    ])
    expect(tabs[0].attributes('aria-selected')).toBe('true')
    expect(tabs[1].attributes('aria-selected')).toBe('false')
  })

  it('criterion 9: switching a tab reloads the list with that status filter', async () => {
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const tabs = wrapper.findAll('[role="tab"]')
    await tabs[1].trigger('click') // APPROVED
    await flushPromises()
    expect(mockGetPending).toHaveBeenLastCalledWith('APPROVED')
    expect(tabs[1].attributes('aria-selected')).toBe('true')
    await tabs[3].trigger('click') // ALL
    await flushPromises()
    expect(mockGetPending).toHaveBeenLastCalledWith('ALL')
    expect(tabs[3].attributes('aria-selected')).toBe('true')
  })

  it('criterion 10: an approved row shows the decision, the reviewer and the review date', async () => {
    mockGetPending.mockResolvedValue([
      {
        id: 'sub-9',
        processKey: 'p9',
        name: 'Process Nine',
        status: 'APPROVED',
        submittedBy: 'e58f1a42-0000-4000-8000-000000000009',
        submittedByUsername: 'bob',
        submittedByFullName: 'Bob Reviewer',
        submittedByEmail: 'bob@test.com',
        submittedAt: '2026-08-01T10:00:00Z',
        reviewedByUsername: 'carol',
        reviewedAt: '2026-08-02T12:00:00Z',
        rejectReason: null,
        previousSubmissionId: null,
      },
    ])
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('approvedLabel')
    expect(text).toContain('carol')
    expect(text).toContain('2026-08-02T12:00:00Z')
    // no approve/reject ACTION buttons on a reviewed row
    expect(wrapper.findAll('tbody button')).toHaveLength(0)
  })

  it('criterion 10: a rejected row shows the decision, reviewer, date and the reject reason', async () => {
    mockGetPending.mockResolvedValue([
      {
        id: 'sub-10',
        processKey: 'p10',
        name: 'Process Ten',
        status: 'REJECTED',
        submittedBy: 'e58f1a42-0000-4000-8000-000000000010',
        submittedByUsername: 'bob',
        submittedByFullName: 'Bob Reviewer',
        submittedByEmail: 'bob@test.com',
        submittedAt: '2026-08-01T10:00:00Z',
        reviewedByUsername: 'carol',
        reviewedAt: '2026-08-02T12:00:00Z',
        rejectReason: 'Key collides with an existing process',
        previousSubmissionId: null,
      },
    ])
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('rejectedLabel')
    expect(text).toContain('carol')
    expect(text).toContain('Key collides with an existing process')
    expect(wrapper.findAll('tbody button')).toHaveLength(0)
  })

  it('criterion 11: the model preview opens from a REVIEWED row, not only from pending ones', async () => {
    mockGetBpmn.mockResolvedValue('<bpmn:definitions />')
    mockGetPending.mockResolvedValue([
      {
        id: 'sub-11',
        processKey: 'p11',
        name: 'Process Eleven',
        status: 'APPROVED',
        submittedBy: 'e58f1a42-0000-4000-8000-000000000011',
        submittedByUsername: 'bob',
        submittedByFullName: 'Bob Reviewer',
        submittedByEmail: 'bob@test.com',
        submittedAt: '2026-08-01T10:00:00Z',
        reviewedByUsername: 'carol',
        reviewedAt: '2026-08-02T12:00:00Z',
        rejectReason: null,
        previousSubmissionId: null,
      },
    ])
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(mockGetBpmn).toHaveBeenCalledWith('sub-11')
    expect(wrapper.find('.fixed.inset-0').exists()).toBe(true)
  })

  it('criterion 12: a KNOWN error code renders the locale text with the params injected', async () => {
    mockGetPending.mockRejectedValue({
      response: { data: { code: 'PENDING_SUBMISSION_EXISTS', params: { processKey: 'p1' }, message: 'A pending submission for key p1 already exists' } },
    })
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    expect(wrapper.text()).toContain('errors.PENDING_SUBMISSION_EXISTS:p1')
  })

  it('criterion 12: an UNKNOWN error code falls back to the server message', async () => {
    mockGetPending.mockRejectedValue({
      response: { data: { code: 'SOMETHING_NEW_FROM_THE_SERVER', params: { x: 1 }, message: 'A brand new server-side message' } },
    })
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    expect(wrapper.text()).toContain('A brand new server-side message')
  })

  it('criterion 12: approval failures are translated by code too', async () => {
    mockApprove.mockRejectedValue({
      response: { data: { code: 'SUBMISSION_ALREADY_REVIEWED', params: { submissionId: 'sub-1', status: 'APPROVED' }, message: 'The submission was already reviewed' } },
    })
    const wrapper = mount(SubmissionQueue)
    await flushPromises()
    const approveBtn = wrapper.findAll('button').find((b) => b.text() === 'approve')!
    await approveBtn.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('errors.SUBMISSION_ALREADY_REVIEWED:sub-1|APPROVED')
  })
})
