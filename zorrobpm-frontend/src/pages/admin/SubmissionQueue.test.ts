// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SubmissionQueue from './SubmissionQueue.vue'

const mockGetPending = vi.hoisted(() => vi.fn())
const mockApprove = vi.hoisted(() => vi.fn().mockResolvedValue({}))
const mockReject = vi.hoisted(() => vi.fn().mockResolvedValue({}))
vi.mock('@/services/submissionService', () => ({
  getPendingSubmissions: mockGetPending,
  approveSubmission: mockApprove,
  rejectSubmission: mockReject,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

const PENDING = [
  {
    id: 'sub-1',
    processKey: 'p1',
    name: 'Process One',
    status: 'PENDING',
    submittedBy: 'alice',
    submittedAt: '2026-08-01T10:00:00Z',
    rejectReason: null,
    previousSubmissionId: null,
  },
]

describe('SubmissionQueue (WO-ACL-6 criterion 6)', () => {
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
    expect(text).toContain('alice')
    expect(text).toContain('approve')
    expect(text).toContain('reject')
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