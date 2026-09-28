/**
 * WO-UI-23 criteria 1+3 — the REAL ProcessInstanceDetail page in a real
 * Chromium: a long JSON variable opens the inspector modal with its FULL
 * value (not the truncated cell), and the cancel button carries an icon.
 *
 * Mount pattern (real page + real vue-i18n + mocked services) is copied from
 * JsonPlaceholder.ui20.browser.test.ts: vue-i18n is deliberately NOT mocked
 * (a t()-stub could never throw, P-54 class of false-green tests).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

// The REAL app stylesheet (Tailwind), exactly as main.ts loads it — without
// this no utility class (truncate/max-w-xs/fixed/…) computes in the test
// (same import as visual-geometry.browser.test.ts).
import '@/style.css'

const holder = vi.hoisted(() => ({
  json: JSON.stringify([{ employeeId: 301, employeeIin: '811231301234', note: 'browser-tail-marker-zzz' }]),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u-owner', username: 'alice' }, isSuperAdmin: true }),
}))

vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  deployProcessDefinition: vi.fn().mockResolvedValue({}),
  addProcessDefinitionVersion: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/services/taskService', () => ({
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, cancelled: false, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
  cancelProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1' }),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({
    data: [{ name: 'steps', type: 'JSON', value: holder.json }],
  }),
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
vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn().mockResolvedValue({}),
  removeMember: vi.fn().mockResolvedValue({}),
  searchMemberCandidates: vi.fn().mockResolvedValue([]),
  addMember: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

function makeI18n(locale: string) {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    messages: { ru, en, kz },
  })
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

function until(cond: () => boolean, timeout = 8000): Promise<void> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const tick = () => {
      if (cond()) return resolve()
      if (Date.now() - start > timeout) return reject(new Error('timeout waiting for condition'))
      requestAnimationFrame(tick)
    }
    tick()
  })
}

describe('WO-UI-23: variable inspector + cancel icon in a real browser', () => {
  it('long JSON variable opens the inspector with its full value; cancel button has an icon', async () => {
    const i18n = makeI18n('ru')
    const host = document.createElement('div')
    host.style.position = 'absolute'
    host.style.top = '0'
    host.style.left = '0'
    host.style.display = 'flex'
    host.style.width = '1200px'
    host.style.height = '700px'
    document.body.appendChild(host)
    hosts.push(host)

    const wrapper = mount(ProcessInstanceDetail, {
      attachTo: host,
      global: { stubs: { teleport: true }, plugins: [createPinia(), i18n] },
    })
    mounted.push(wrapper)
    await flushPromises()

    // Switch to the variables tab (same user path as the screenshot report).
    const vm = wrapper.vm as unknown as { activeTab: string }
    vm.activeTab = 'variables'
    await flushPromises()
    await until(() => wrapper.find('[data-testid="inspect-variable-steps"]').exists())

    // Geometry, not just classes: the truncate span inside the max-w-xs cell
    // is REALLY clipped (computed overflow hidden + ellipsis) — the old
    // truncate-on-td never clipped in a live browser (table-cell computed
    // overflow stays visible; the cell stretched to 718px), which is exactly
    // the user-visible complaint this WO fixes.
    const span = wrapper.find('td.max-w-xs span.truncate')
    expect(span.exists(), 'truncated value span renders').toBe(true)
    const spanStyle = getComputedStyle(span.element as HTMLElement)
    expect(spanStyle.overflow).toBe('hidden')
    expect(spanStyle.textOverflow).toBe('ellipsis')
    expect(spanStyle.whiteSpace).toBe('nowrap')

    // …and the inspector really opens with the FULL value.
    await wrapper.find('[data-testid="inspect-variable-steps"]').trigger('click')
    await flushPromises()
    await until(() => wrapper.find('[data-testid="variable-inspector"]').exists())
    const modalText = wrapper.find('[data-testid="variable-inspector-value"]').text()
    expect(modalText).toContain('browser-tail-marker-zzz')

    // The modal is a real overlay: fixed inset-0, and its <pre> wraps the
    // full value onto multiple lines (not a single nowrap line like the cell).
    const overlay = wrapper.find('[data-testid="variable-inspector"]')
    const overlayStyle = getComputedStyle(overlay.element as HTMLElement)
    expect(overlayStyle.position).toBe('fixed')
    const preStyle = getComputedStyle(wrapper.find('[data-testid="variable-inspector-value"]').element as HTMLElement)
    expect(preStyle.whiteSpace).toContain('pre')

    // Cancel button: icon (svg) + destructive intent kept.
    const cancelBtn = wrapper.find('[data-testid="cancel-instance-btn"]')
    expect(cancelBtn.exists(), 'cancel button renders').toBe(true)
    expect(cancelBtn.find('svg').exists(), 'cancel button carries an icon').toBe(true)
  })
})
