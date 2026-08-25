// @vitest-environment jsdom
/**
 * WO-ACL-11 criterion 3: the last breadcrumb on the process card is the process
 * name and it changes when the route switches to another process.
 *
 * P-54: this is tested on the REAL tree — MainLayout + <router-view> + the real
 * ProcessDefinitionDetail page — NOT on a synthetically mounted BreadcrumbNav
 * with a hand-provided value. BreadcrumbNav sits ABOVE <router-view>, so the old
 * provide/inject mechanism could never deliver the name; its tests passed only
 * because they mounted the component alone (the ACL-8/ACL-10 defect lived to the
 * third round exactly because of that). The name now travels through the
 * breadcrumb store, which the page fills on load and clears on unmount.
 */
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import MainLayout from './MainLayout.vue'
import ProcessDefinitionDetail from '@/pages/processes/ProcessDefinitionDetail.vue'
import { useBreadcrumbStore } from '@/stores/breadcrumb'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'

const mockListMembers = vi.hoisted(() => vi.fn().mockResolvedValue([]))
vi.mock('@/services/adminService', () => ({
  listMembers: mockListMembers,
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue(null),
  addProcessDefinitionVersion: vi.fn().mockResolvedValue({ id: 'def2', key: 'test-proc', version: 2 }),
}))
vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  saveElementSchema: vi.fn(),
  getForm: vi.fn(),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn(),
}))

const mockAuth = vi.hoisted(() => ({ id: 'u-owner', isSuperAdmin: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: mockAuth.id }, isSuperAdmin: mockAuth.isSuperAdmin }),
}))

// The process store is a mutable REACTIVE state object so the test can switch
// the definition (and its name) while the page is mounted — useBreadcrumbLabel
// watches the definition through the store, so a plain non-reactive object
// would never trigger the watcher (the old explicit setProcessName call in
// loadDefinition masked exactly that).
const mockProcessState = vi.hoisted(() => ({ state: null as unknown as {
  currentDefinition: { id: string; key: string; version: number; name: string; sha256: string; createdAt: string; startFormKey: string | null }
  currentStructure: unknown
  currentVersions: unknown[]
  loading: boolean
  error: unknown
  fetchDefinition: ReturnType<typeof vi.fn>
  fetchStructure: ReturnType<typeof vi.fn>
  fetchVersions: ReturnType<typeof vi.fn>
  startInstance: ReturnType<typeof vi.fn>
} }))
vi.mock('@/stores/process', async () => {
  const { reactive } = await import('vue')
  mockProcessState.state = reactive({
    currentDefinition: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] },
    currentVersions: [] as { id: string; version: number; createdAt: string }[],
    loading: false,
    error: null,
    fetchDefinition: vi.fn(),
    fetchStructure: vi.fn(),
    fetchVersions: vi.fn(),
    startInstance: vi.fn().mockResolvedValue({ id: 'inst-1' }),
  })
  return { useProcessStore: () => mockProcessState.state }
})

const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

// Sidebar/Header/BPMN widgets are not under test here — real MainLayout and real
// BreadcrumbNav are; the page's heavy viewers are stubbed like in the page tests.
vi.mock('@/widgets/shared/SidebarNavShadcn.vue', () => ({ default: { template: '<div class="sidebar-stub" />' } }))
vi.mock('@/widgets/shared/HeaderBar.vue', () => ({ default: { template: '<div class="header-stub" />' } }))
vi.mock('@/widgets/bpmn/BpmnViewer.vue', () => ({ default: { template: '<div class="bpmn-stub" />' } }))
vi.mock('@/widgets/shared/SchemaEditorPanel.vue', () => ({ default: { template: '<div class="schema-stub" />' } }))

const i18n = createI18n({ legacy: false, locale: 'ru', fallbackLocale: 'en', messages: { en, ru, kz } })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: MainLayout,
        children: [
          {
            path: 'processes/definitions',
            name: 'process-definitions',
            component: { template: '<div>List</div>' },
            meta: { titleKey: 'processDefinitions' },
          },
          {
            path: 'processes/definitions/:id',
            name: 'process-definition-detail',
            component: ProcessDefinitionDetail,
            meta: { titleKey: 'processDefinition', parentTitleKey: 'processDefinitions', parentTo: { name: 'process-definitions' } },
          },
        ],
      },
    ],
  })
}

async function mountApp(path: string, router: ReturnType<typeof makeRouter>) {
  await router.push(path)
  await router.isReady()
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(MainLayout, {
    global: { stubs: { teleport: true }, plugins: [router, pinia, i18n] },
  })
  await flushPromises()
  return { wrapper, pinia }
}

describe('WO-ACL-11 criterion 3: breadcrumb on the real MainLayout + router-view tree', () => {
  beforeAll(() => {
    // jsdom has no matchMedia — MainLayout's useIsMobile() needs it
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
  })

  beforeEach(() => {
    vi.clearAllMocks()
    mockAuth.id = 'u-owner'
    mockAuth.isSuperAdmin = false
    mockProcessState.state.currentDefinition = { id: 'def1', key: 'test-proc', version: 1, name: 'Test', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null }
  })

  it('criterion 3: the card page shows the process name as the last crumb, under the real layout', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/processes/definitions/def1', router)

    const nav = wrapper.find('nav')
    expect(nav.exists()).toBe(true)
    const items = wrapper.findAll('li')
    // parent crumb is the translated section name, last crumb is the process name
    expect(items[0].find('a').text()).toBe('Схемы процессов')
    expect(items[1].find('span').text()).toBe('Test')
  })

  it('criterion 3: the crumb updates when the route switches to another process', async () => {
    const router = makeRouter()
    const { wrapper } = await mountApp('/processes/definitions/def1', router)
    expect(wrapper.findAll('li')[1].find('span').text()).toBe('Test')

    // the next definition arrives with a different name (same route, new :id)
    mockProcessState.state.currentDefinition = { id: 'def2', key: 'test-proc', version: 2, name: 'Test 2', sha256: 'def', createdAt: '2026-01-02', startFormKey: null }
    await router.push('/processes/definitions/def2')
    await flushPromises()

    expect(wrapper.findAll('li')[1].find('span').text()).toBe('Test 2')
  })

  it('criterion 3: leaving the card clears the store — the next page never shows a stale name', async () => {
    const router = makeRouter()
    const { wrapper, pinia } = await mountApp('/processes/definitions/def1', router)
    expect(useBreadcrumbStore().crumbLabel).toBe('Test')

    await router.push('/processes/definitions')
    await flushPromises()

    // store cleared by useBreadcrumbLabel's onUnmounted — a stale label can not leak into another page
    expect(useBreadcrumbStore().crumbLabel).toBeNull()
    // list page has a single crumb → the panel is hidden
    expect(wrapper.find('nav').exists()).toBe(false)
    void pinia
  })
})