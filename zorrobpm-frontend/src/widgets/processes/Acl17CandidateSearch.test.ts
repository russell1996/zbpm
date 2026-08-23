// @vitest-environment jsdom
/**
 * WO-ACL-17 criterion 4 — the add-member dialog lets the user search by what
 * the dialog itself DISPLAYS: the full name (and email), not only the login.
 * Backend does the matching (login/name/email substring); the dialog must pass
 * the typed name through verbatim and render the results by that name.
 * The placeholder/hint keys promise "name, username or email" (locales updated
 * in this WO) and stay wired to the input.
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
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))

describe('MemberAddDialog (WO-ACL-17 criterion 4: search by the visible name)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockSearch.mockResolvedValue([
      { userId: 'u-k', username: 'kpesha', fullName: 'Кирилл Пешков', email: 'kirill@t.com' },
    ])
  })

  it('a typed FULL NAME is sent as the query verbatim and the row shows that name', async () => {
    const wrapper = mount(MemberAddDialog, {
      props: { open: true, processKey: 'p', members: [] },
    })
    await wrapper.get('input').setValue('Кирилл Пеш')
    await flushPromises()
    expect(mockSearch).toHaveBeenCalledWith('p', 'Кирилл Пеш')

    const row = wrapper.findAll('button').find((b) => b.text().includes('Пешков'))!
    expect(row.text()).toContain('Кирилл Пешков')
  })

  it('the search field still advertises the widened scope (placeholder key)', async () => {
    const wrapper = mount(MemberAddDialog, {
      props: { open: true, processKey: 'p', members: [] },
    })
    await flushPromises()
    // locales updated in WO-ACL-17 to "name, username or email"; the input keeps its key
    expect(wrapper.get('input').attributes('placeholder')).toBe('searchCandidatePlaceholder')
  })
})
