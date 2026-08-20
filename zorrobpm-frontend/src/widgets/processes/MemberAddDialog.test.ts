// @vitest-environment jsdom
/**
 * WO-ACL-14 criteria 10-12 — MemberAddDialog:
 *  10 — the dialog lists search results (WO-ACL-15: fullName/email come from
 *       the contract — see the ACL-14 report escalation, resolved here) and the
 *       selection is row-based, not id-typing;
 *  11 — candidates already in the members list are marked (alreadyMember) and
 *       cannot be selected again;
 *  12 — on success the dialog closes (close emitted); on error it STAYS open
 *       with the reason; the submit button is locked from press to response —
 *       five rapid clicks fire ONE request (handler guard, not only disabled).
 * WO-ACL-15 criteria 4-5:
 *  4 — the row shows the name and the email, the login stays as a detail;
 *  5 — a candidate without name/email renders no "null" and no empty lines.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MemberAddDialog from './MemberAddDialog.vue'

const mockSearch = vi.hoisted(() => vi.fn())
const mockAdd = vi.hoisted(() => vi.fn())
vi.mock('@/services/adminService', () => ({
  searchMemberCandidates: mockSearch,
  addMember: mockAdd,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

const CANDIDATES = [
  { userId: 'u-carol', username: 'carol', fullName: 'Carol C.', email: 'carol@t.com' },
  { userId: 'u-dave', username: 'dave', fullName: '', email: '' },
  { userId: 'u-bob', username: 'bob', fullName: 'Bob B.', email: 'bob@t.com' },
]
const MEMBERS = [
  { userId: 'u-owner', username: 'alice', fullName: 'Alice A.', email: 'a@t.com', role: 'OWNER', addedBy: null, addedAt: '2026-01-01', processKey: 'p' },
  { userId: 'u-bob', username: 'bob', fullName: 'Bob B.', email: 'b@t.com', role: 'VIEWER', addedBy: 'u-owner', addedAt: '2026-01-02', processKey: 'p' },
]

function renderDialog(props: Record<string, unknown> = {}) {
  return mount(MemberAddDialog, {
    props: { open: true, processKey: 'p', members: MEMBERS, ...props },
  })
}

async function typeQuery(wrapper: ReturnType<typeof renderDialog>, q: string) {
  await wrapper.get('input').setValue(q)
  await flushPromises()
}

describe('MemberAddDialog (WO-ACL-14 criteria 10-12)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockSearch.mockResolvedValue([...CANDIDATES])
    mockAdd.mockResolvedValue({ userId: 'u-carol', username: 'carol', role: 'VIEWER', fullName: null, email: null, addedBy: 'u-owner', addedAt: '2026-01-03', processKey: 'p' })
  })

  it('criterion 10: candidates are listed as rows and picked by clicking the row', async () => {
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'car')
    expect(mockSearch).toHaveBeenCalledWith('p', 'car')

    const rows = wrapper.findAll('button').filter((b) => b.text().includes('carol'))
    expect(rows.length).toBeGreaterThan(0)
    // row click selects the candidate — no manual id typing anywhere
    await rows[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('selected')
  })

  it('criterion 4: the row shows the name and the email, the login stays as a detail', async () => {
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'car')
    const carolRow = wrapper.findAll('button').find((b) => b.text().includes('carol'))!
    // name first, then the detail line "login · email"
    expect(carolRow.text()).toContain('Carol C.')
    expect(carolRow.text()).toContain('carol · carol@t.com')
    // the raw userId must NOT be rendered as the detail (ACL-15 replaced it
    // with the login/email pair)
    expect(carolRow.text()).not.toContain('u-carol')
  })

  it('criterion 5: candidate without name/email renders no "null" and no empty lines', async () => {
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'dave')
    const daveRow = wrapper.findAll('button').find((b) => b.text().includes('dave'))!
    // bare username, name/email absent — no "null" anywhere in the dialog
    expect(daveRow.text()).toContain('dave')
    expect(wrapper.text()).not.toContain('null')
    // no empty detail line: the detail span (name/email line) is NOT rendered
    expect(daveRow.find('span.text-xs').exists()).toBe(false)
  })

  it('criterion 5: candidate with email but no name shows username + email, still no "null"', async () => {
    mockSearch.mockResolvedValue([{ userId: 'u-erin', username: 'erin', fullName: '', email: 'erin@t.com' }])
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'erin')
    const erinRow = wrapper.findAll('button').find((b) => b.text().includes('erin'))!
    expect(erinRow.text()).toContain('erin')
    expect(erinRow.text()).toContain('erin@t.com')
    expect(wrapper.text()).not.toContain('null')
  })

  it('criterion 8: the list is shown IMMEDIATELY on open — empty query, no typing required', async () => {
    // WO-ACL-15 part B: the dialog requests the first page with an EMPTY q on open
    const wrapper = renderDialog()
    await flushPromises()
    expect(mockSearch).toHaveBeenCalledWith('p', '')
    const rows = wrapper.findAll('button').filter((b) => b.text().includes('carol'))
    expect(rows.length).toBeGreaterThan(0)
  })

  it('criterion 8: typing narrows the list (a short query hits the API too)', async () => {
    const wrapper = renderDialog()
    await flushPromises()
    mockSearch.mockClear()
    await typeQuery(wrapper, 'ca')
    expect(mockSearch).toHaveBeenCalledWith('p', 'ca')
    expect(mockSearch).toHaveBeenCalledTimes(1)
  })

  it('criterion 8: a full page (20) is labelled as truncated, not silently cut', async () => {
    const twenty = Array.from({ length: 20 }, (_, i) => ({
      userId: 'u-' + i, username: 'user' + i, fullName: '', email: '',
    }))
    mockSearch.mockResolvedValue(twenty)
    const wrapper = renderDialog()
    await flushPromises()
    expect(wrapper.text()).toContain('candidatesTruncated')
  })

  it('criterion 10: empty result shows the "no candidates" text', async () => {
    mockSearch.mockResolvedValue([])
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'xyz')
    expect(wrapper.text()).toContain('noCandidates')
  })

  it('criterion 11: an already-added candidate is marked and cannot be selected', async () => {
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'bob')
    const bobRow = wrapper.findAll('button').find((b) => b.text().includes('bob'))!
    // marked with the alreadyMember label
    expect(bobRow.text()).toContain('alreadyMember')
    expect(bobRow.attributes('disabled')).toBeDefined()
    // clicking it does NOT select: no role row, no "selected" marker
    await bobRow.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('selected')
    // the submit button stays disabled (no selection)
    const submit = wrapper.findAll('button').find((b) => b.text() === 'addMember')!
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('criterion 12: success closes the dialog (close emitted) and reports the member', async () => {
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'carol')
    await wrapper.findAll('button').find((b) => b.text().includes('carol'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text() === 'addMember')!.trigger('click')
    await flushPromises()
    expect(mockAdd).toHaveBeenCalledWith('p', 'u-carol', 'VIEWER')
    expect(mockToast.success).toHaveBeenCalledWith('memberAdded')
    expect(wrapper.emitted('added')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('criterion 12: on error the dialog STAYS open and shows the reason', async () => {
    mockAdd.mockRejectedValue(new Error('boom'))
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'carol')
    await wrapper.findAll('button').find((b) => b.text().includes('carol'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text() === 'addMember')!.trigger('click')
    await flushPromises()
    // stays open: no close emitted, reason visible, dialog still rendered
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.text()).toContain('boom')
    expect(wrapper.find('input').exists()).toBe(true)
  })

  it('criterion 12: five rapid clicks fire ONE request (handler guard, not only :disabled)', async () => {
    let resolveAdd: (v: unknown) => void
    mockAdd.mockReturnValue(new Promise((r) => { resolveAdd = r }))
    const wrapper = renderDialog()
    await typeQuery(wrapper, 'carol')
    await wrapper.findAll('button').find((b) => b.text().includes('carol'))!.trigger('click')
    await flushPromises()

    const submit = wrapper.findAll('button').find((b) => b.text() === 'addMember')!
    // five clicks in a ROW, faster than the reactive disabled update — the
    // handler guard (`if (addingMember.value) return`) is the only thing that
    // can stop the 2nd..5th call (P-46: on ACL-11 removing that guard failed
    // to redden any test because :disabled alone held the line)
    for (let i = 0; i < 5; i++) {
      void submit.trigger('click')
    }
    await flushPromises()
    expect(mockAdd).toHaveBeenCalledTimes(1)

    resolveAdd!({ userId: 'u-carol', username: 'carol', role: 'VIEWER', fullName: null, email: null, addedBy: 'u-owner', addedAt: '2026-01-03', processKey: 'p' })
    await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})