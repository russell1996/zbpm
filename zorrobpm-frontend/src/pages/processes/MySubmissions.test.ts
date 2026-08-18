// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MySubmissions from './MySubmissions.vue'

const mockGetMy = vi.hoisted(() => vi.fn())
vi.mock('@/services/submissionService', () => ({
  getMySubmissions: mockGetMy,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))

const SUBMISSIONS = [
  {
    id: 'sub-1',
    processKey: 'p1',
    name: 'Process One',
    status: 'REJECTED',
    submittedBy: 'alice',
    submittedAt: '2026-08-01T10:00:00Z',
    rejectReason: 'Duplicate of existing process',
    previousSubmissionId: null,
  },
  {
    id: 'sub-2',
    processKey: 'p2',
    name: 'Process Two',
    status: 'PENDING',
    submittedBy: 'alice',
    submittedAt: '2026-08-02T10:00:00Z',
    rejectReason: null,
    previousSubmissionId: null,
  },
  {
    id: 'sub-3',
    processKey: 'p3',
    name: 'Process Three',
    status: 'APPROVED',
    submittedBy: 'alice',
    submittedAt: '2026-08-03T10:00:00Z',
    rejectReason: null,
    previousSubmissionId: null,
  },
]

describe('MySubmissions (WO-ACL-6 criterion 6)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetMy.mockResolvedValue([...SUBMISSIONS])
  })

  it('lists MY submissions with status badges (PENDING / REJECTED / APPROVED)', async () => {
    const wrapper = mount(MySubmissions)
    await flushPromises()
    expect(mockGetMy).toHaveBeenCalledTimes(1)
    const text = wrapper.text()
    expect(text).toContain('p1')
    expect(text).toContain('p2')
    expect(text).toContain('p3')
    expect(text).toContain('statusPending')
    expect(text).toContain('statusRejected')
    expect(text).toContain('statusApproved')
  })

  it('a REJECTED submission shows the rejection reason', async () => {
    const wrapper = mount(MySubmissions)
    await flushPromises()
    expect(wrapper.text()).toContain('Duplicate of existing process')
  })

  it('WO-ACL-11 criterion 35: the "resubmit" button is GONE — no row offers it', async () => {
    const wrapper = mount(MySubmissions)
    await flushPromises()
    const buttons = wrapper.findAll('button').filter((b) => b.text() === 'resubmit')
    expect(buttons.length).toBe(0)
    // the action column itself is gone too (the header cell is empty)
    expect(wrapper.text()).not.toContain('resubmit')
  })

  it('shows an empty-state when there are no submissions', async () => {
    mockGetMy.mockResolvedValue([])
    const wrapper = mount(MySubmissions)
    await flushPromises()
    expect(wrapper.text()).toContain('noSubmissions')
  })

  it('WO-ACL-11 criterion 6: no detail page exists for a submission — rows stay non-navigable, no "view" button', async () => {
    const wrapper = mount(MySubmissions)
    await flushPromises()
    // no view button, no clickable rows (documented in the report as the
    // "no card exists" exception per the WO)
    expect(wrapper.text()).not.toContain('view')
    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(3)
    for (const row of rows) {
      expect(row.attributes('tabindex')).toBeUndefined()
    }
    await rows[0].trigger('click')
  })
})