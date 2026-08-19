/**
 * WO-ACL-14 — browser-geometry checks + LIVE screenshots.
 *
 * jsdom cannot compute layout (P-53), so the visual criteria — visible tab
 * underline (2), narrow-screen ribbon scroll (5), timer status as ONE element
 * (8), full ids in tables (17), wider drawer + inner scrolling (20/21), long
 * reject reason readable in full (22) — are asserted in a real headless
 * Chromium (vitest browser mode + Playwright provider, WO-TEST-6 infra) and
 * captured as git-tracked screenshots into shots/.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { page } from 'vitest/browser'
import ProcessDefinitionDetail from '@/pages/processes/ProcessDefinitionDetail.vue'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import TimerList from '@/pages/timers/TimerList.vue'
import AppDrawer from '@/widgets/shared/AppDrawer.vue'
import MySubmissions from '@/pages/processes/MySubmissions.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

// The REAL app stylesheet (Tailwind) and fonts, exactly as main.ts loads them.
import '@/style.css'
import '@fontsource/golos-text/400.css'
import '@fontsource/golos-text/500.css'
import '@fontsource/golos-text/600.css'
import '@fontsource/golos-text/700.css'

const SHOT_DIR = 'shots'

// ---- file-level mocks (real i18n, real Tailwind — only services are mocked) ----

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'def1' }, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

const mockAuth = vi.hoisted(() => ({ isSuperAdmin: true }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u-owner', username: 'alice' }, isSuperAdmin: mockAuth.isSuperAdmin }),
}))

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def1', key: 'order', version: 1, name: 'Order', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: { id: 'def1', key: 'order', version: 1, name: 'Order', documentation: null, nodes: [], flows: [] },
    currentVersions: [],
    currentInstance: { id: 'pi-1', parentActivityId: null, processDefinitionId: 'def1', startedAt: '2026-01-01', completedAt: null, processName: 'Order', processKey: 'order', processVersion: 1 },
    activities: [],
    instances: [],
    loading: false,
    error: null,
    fetchDefinition: vi.fn(),
    fetchStructure: vi.fn(),
    fetchVersions: vi.fn(),
    fetchInstance: vi.fn(),
    fetchActivities: vi.fn(),
    fetchInstances: vi.fn(),
    fetchDefinitions: vi.fn(),
    fetchSubprocesses: vi.fn(),
    fetchVariables: vi.fn(),
    startInstance: vi.fn(),
    clearCurrent: vi.fn(),
    handleEvent: vi.fn(),
  }),
}))
vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setProcessName: vi.fn() }),
}))

const mockListMembers = vi.hoisted(() => vi.fn())
vi.mock('@/services/adminService', () => ({
  listMembers: mockListMembers,
  changeMemberRole: vi.fn().mockResolvedValue({}),
  removeMember: vi.fn().mockResolvedValue({}),
  searchMemberCandidates: vi.fn().mockResolvedValue([
    { userId: 'u-annette', username: 'annette' },
    { userId: 'u-bob', username: 'bob' },
  ]),
  addMember: vi.fn().mockResolvedValue({}),
}))

const mockGetTimers = vi.hoisted(() => vi.fn())
vi.mock('@/services/timerService', () => ({
  getTimerJobs: mockGetTimers,
}))

const mockGetMySubmissions = vi.hoisted(() => vi.fn())
vi.mock('@/services/submissionService', () => ({
  getMySubmissions: mockGetMySubmissions,
  submitProcessSubmission: vi.fn().mockResolvedValue({}),
  getPendingSubmissions: vi.fn().mockResolvedValue([]),
  approveSubmission: vi.fn().mockResolvedValue({}),
  rejectSubmission: vi.fn().mockResolvedValue({}),
  getSubmissionBpmn: vi.fn().mockResolvedValue(''),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue(null),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue({ id: 'def1', key: 'order', version: 1, name: 'Order', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null }),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  deployProcessDefinition: vi.fn().mockResolvedValue({}),
  addProcessDefinitionVersion: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'order', version: 1, elements: [] }),
  saveElementSchema: vi.fn().mockResolvedValue(undefined),
  getForm: vi.fn().mockResolvedValue(null),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn().mockResolvedValue({}),
  deployForm: vi.fn().mockResolvedValue(undefined),
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
  generateSchema: vi.fn().mockResolvedValue('{}'),
}))
vi.mock('@/services/taskService', () => ({
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

// ---- helpers ----

function makeI18n(locale: string) {
  return createI18n({ legacy: false, locale, fallbackLocale: 'en', messages: { ru, en, kz } })
}

function mountOn<C>(component: C, opts: Record<string, unknown> = {}): VueWrapper {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.inset = '0'
  document.body.appendChild(host)
  const wrapper = mount(component as never, {
    attachTo: host,
    ...opts,
  }) as VueWrapper
  return wrapper
}

async function until(fn: () => boolean, timeout = 3000) {
  const start = Date.now()
  while (!fn()) {
    if (Date.now() - start > timeout) throw new Error('until() timed out')
    await new Promise((r) => setTimeout(r, 20))
  }
}

const LONG_ID = '4394c7b1-aaaa-4000-8000-000000000001'
const LONG_REASON = 'документы не приложены: счёт-фактура № 12345678901234567890 от 2026-08-01 отсутствует в системе и не может быть восстановлен автоматически, пожалуйста приложите оригинал'

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  mockListMembers.mockResolvedValue([
    { userId: 'u-owner', username: 'alice', fullName: 'Alice A.', email: 'alice@test.com', role: 'OWNER', addedBy: null, addedAt: '2026-01-01', processKey: 'order' },
    { userId: 'u-bob', username: 'bob', fullName: 'Bob B.', email: 'bob@test.com', role: 'VIEWER', addedBy: 'u-owner', addedAt: '2026-01-02', processKey: 'order' },
  ])
  mockGetTimers.mockResolvedValue({
    data: [
      { id: LONG_ID, processInstanceId: 'inst-11111111-aaaa-4000-8000-000000000001', activityId: 'Activity_1abc', dueAt: '2026-01-01', fired: true, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-01' },
      { id: 'timer-2', processInstanceId: null, activityId: 'act-only', dueAt: '2026-01-02', fired: false, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-02' },
    ],
    totalElements: 2,
  })
  mockGetMySubmissions.mockResolvedValue([
    { id: 'sub-1', processKey: 'vacation', name: 'Vacation', status: 'APPROVED', submittedBy: 'alice', submittedAt: '2026-08-01T10:00:00Z', rejectReason: null, previousSubmissionId: null },
    { id: 'sub-2', processKey: 'purchase', name: 'Purchase', status: 'REJECTED', submittedBy: 'alice', submittedAt: '2026-08-02T10:00:00Z', rejectReason: LONG_REASON, previousSubmissionId: null },
  ])
})

describe('WO-ACL-14 criterion 2 — visible underline on BOTH tab strips (screenshots)', () => {
  it('ProcessDefinitionDetail: the active tab has a visible, non-transparent bottom border', async () => {
    const wrapper = mountOn(ProcessDefinitionDetail, { global: { plugins: [makeI18n('ru'), createPinia()] } })
    await flushPromises()

    const nav = wrapper.find('nav[role="tablist"]').element
    const active = wrapper.find('nav[role="tablist"] button[aria-selected="true"]').element
    const nr = nav.getBoundingClientRect()
    const ar = active.getBoundingClientRect()
    const style = getComputedStyle(active)
    // the underline is border-b-2 with border-primary — 2px, real color, and the
    // button reaches the nav's bottom edge (the -mb-px overlap, not hidden under it)
    expect(style.borderBottomWidth).toBe('2px')
    expect(style.borderBottomColor).not.toBe('rgba(0, 0, 0, 0)')
    expect(style.borderBottomColor).not.toBe('transparent')
    expect(ar.bottom).toBeGreaterThanOrEqual(nr.bottom - 2)
    expect(ar.bottom).toBeLessThanOrEqual(nr.bottom + 2)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-definition-tabs.png` })
    wrapper.unmount()
  })

  it('ProcessInstanceDetail: the active tab has a visible, non-transparent bottom border', async () => {
    const wrapper = mountOn(ProcessInstanceDetail, { global: { plugins: [makeI18n('ru'), createPinia()] } })
    await flushPromises()

    const nav = wrapper.find('nav[role="tablist"]').element
    const active = wrapper.find('nav[role="tablist"] button[aria-selected="true"]').element
    const nr = nav.getBoundingClientRect()
    const ar = active.getBoundingClientRect()
    const style = getComputedStyle(active)
    expect(style.borderBottomWidth).toBe('2px')
    expect(style.borderBottomColor).not.toBe('transparent')
    expect(ar.bottom).toBeGreaterThanOrEqual(nr.bottom - 2)
    expect(ar.bottom).toBeLessThanOrEqual(nr.bottom + 2)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-instance-tabs.png` })
    wrapper.unmount()
  })
})

describe('WO-ACL-14 criterion 5 — narrow screen: ribbon scrolls, active tab stays in view', () => {
  it('at 500px the instance ribbon overflows horizontally and the active tab is visible', async () => {
    await page.viewport(500, 720)
    const wrapper = mountOn(ProcessInstanceDetail, { global: { plugins: [makeI18n('ru'), createPinia()] } })
    await flushPromises()

    const nav = wrapper.find('nav[role="tablist"]').element
    // 7 tabs at ~100px each do not fit into 500px → the ribbon scrolls
    expect(nav.scrollWidth).toBeGreaterThan(nav.clientWidth)
    // the active (first) tab is inside the ribbon's visible area
    const active = wrapper.find('nav[role="tablist"] button[aria-selected="true"]').element
    const nr = nav.getBoundingClientRect()
    const ar = active.getBoundingClientRect()
    expect(ar.left).toBeGreaterThanOrEqual(nr.left - 1)
    expect(ar.right).toBeLessThanOrEqual(nr.right + 1)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-instance-tabs-narrow.png` })
    wrapper.unmount()
  })
})

describe('WO-ACL-14 criterion 8 — timer status is ONE element (icon inside the pill)', () => {
  it('TimerList: the icon sits INSIDE the badge pill, no loose icon next to it', async () => {
    await page.viewport(1280, 720)
    const wrapper = mountOn(TimerList, { global: { plugins: [makeI18n('ru'), createPinia()] } })
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const pill = firstRow.find('span.rounded-full').element
    const svg = firstRow.find('span.rounded-full svg').element
    const pr = pill.getBoundingClientRect()
    const sr = svg.getBoundingClientRect()
    // the icon rectangle is fully INSIDE the pill rectangle
    expect(sr.left).toBeGreaterThanOrEqual(pr.left)
    expect(sr.right).toBeLessThanOrEqual(pr.right)
    expect(sr.top).toBeGreaterThanOrEqual(pr.top)
    expect(sr.bottom).toBeLessThanOrEqual(pr.bottom)
    // and the status cell contains exactly ONE svg (the pill's own icon)
    const cell = firstRow.findAll('td')[3]
    expect(cell.findAll('svg')).toHaveLength(1)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-timer-status.png` })
    wrapper.unmount()
  })
})

describe('WO-ACL-14 criteria 9-12 — member dialog (screenshot with candidates)', () => {
  it('dialog opens from the members tab, lists candidates, marks the already-added one', async () => {
    const wrapper = mountOn(ProcessDefinitionDetail, { global: { plugins: [makeI18n('ru'), createPinia()] } })
    await flushPromises()

    // open the Members tab
    const membersTab = wrapper.findAll('nav[role="tablist"] button').find((b) => b.text() === 'Участники')!
    await membersTab.trigger('click')
    await flushPromises()

    // open the dialog via the "Add member" button
    const addButton = wrapper.findAll('button').find((b) => b.text() === 'Добавить участника')!
    await addButton.trigger('click')
    await flushPromises()

    const input = wrapper.find('input[placeholder*="мин. 3 символа"]')
    expect(input.exists()).toBe(true)
    await input.setValue('ann')
    await flushPromises()

    // both candidates render; bob is already a member → marked, disabled
    await until(() => wrapper.findAll('button').some((b) => b.text().includes('annette')))
    const bobRow = wrapper.findAll('button').find((b) => b.text().includes('bob'))!
    // real ru string (alreadyMember): «Пользователь уже является участником этой формы»
    expect(bobRow.text()).toContain('уже является участником')
    expect((bobRow.element as HTMLButtonElement).disabled).toBe(true)
    const annetteRow = wrapper.findAll('button').find((b) => b.text().includes('annette'))!
    expect((annetteRow.element as HTMLButtonElement).disabled).toBe(false)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-member-dialog.png` })
    wrapper.unmount()
  })
})

describe('WO-ACL-14 criterion 17 — full ids in tables (no truncation)', () => {
  it('TimerList: the id cell shows the WHOLE uuid, not clipped', async () => {
    const wrapper = mountOn(TimerList, { global: { plugins: [makeI18n('ru'), createPinia()] } })
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const idSpan = firstRow.find('span.group').element
    expect(idSpan.textContent).toContain(LONG_ID)
    // the cell does not clip the text: no horizontal overflow of the span itself
    expect(idSpan.scrollWidth).toBeLessThanOrEqual(idSpan.clientWidth + 1)

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-full-ids.png` })
    wrapper.unmount()
  })
})

describe('WO-ACL-14 criteria 20-22 — My Submissions drawer (screenshot)', () => {
  it('panel is capped at max-w-4xl; the long reject reason is readable in full (wrapped, not clipped)', async () => {
    const wrapper = mountOn(AppDrawer, {
      props: { open: true, title: 'Мои заявки' },
      slots: { default: MySubmissions },
      global: { plugins: [makeI18n('ru'), createPinia()] },
    })
    await flushPromises()
    await until(() => !!wrapper.element.querySelector('.text-red-600 span'))

    const panel = wrapper.get('[data-testid="drawer-panel"]').element
    const pr = panel.getBoundingClientRect()
    // max-w-4xl = 56rem = 896px; on a 1280px viewport the panel is capped
    expect(pr.width).toBeLessThanOrEqual(896 + 2)

    const reason = wrapper.element.querySelector('.text-red-600 span') as HTMLElement | null
    expect(reason).not.toBeNull()
    expect(reason!.textContent).toContain(LONG_REASON)
    // wrapped inside the cell: the span does not overflow its box horizontally
    expect(reason!.scrollWidth).toBeLessThanOrEqual(reason!.clientWidth + 1)
    // the table container scrolls horizontally inside the panel when needed
    const scroller = wrapper.element.querySelector('div.overflow-x-auto') as HTMLElement | null
    expect(scroller).not.toBeNull()
    expect(getComputedStyle(scroller!).overflowX).toBe('auto')

    await page.screenshot({ path: `${SHOT_DIR}/wo-acl-14-my-submissions.png` })
    wrapper.unmount()
  })
})