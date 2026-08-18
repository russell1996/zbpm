// @vitest-environment jsdom
/**
 * WO-FE-18: Breadcrumb dedup + back-nav
 * WO-ACL-10 criteria 18-19:
 *   18 — labels come from locale keys (titleKey/parentTitleKey) via t(),
 *        never from raw English literals in router meta;
 *   19 — the last crumb shows the injected process name ref and reacts to it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import BreadcrumbNav from './BreadcrumbNav.vue'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, ru, kz },
})

function makeRouter(routeMeta: Record<string, any>) {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
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
            component: { template: '<div>Detail</div>' },
            meta: routeMeta,
          },
        ],
      },
    ],
  })
}

async function mountOn(router: ReturnType<typeof makeRouter>, path: string) {
  await router.push(path)
  await router.isReady()
  return mount(BreadcrumbNav, {
    global: { plugins: [router, createPinia(), i18n] },
  })
}

describe('WO-FE-18: BreadcrumbNav', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 1: list-rovers — panel NOT rendered
  // ─────────────────────────────────────────────────────────────
  it('CRIT-1: list pages hide breadcrumb panel (1 item → v-if false)', async () => {
    const router = makeRouter({ titleKey: 'processDefinitions' })
    const wrapper = await mountOn(router, '/processes/definitions')

    // Panel should NOT be rendered (breadcrumbs.length = 1, v-if="> 1")
    expect(wrapper.find('nav').exists()).toBe(false)
    expect(wrapper.find('ol').exists()).toBe(false)
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 2: detail-rovers — panel shows 2 items, first clickable
  // ─────────────────────────────────────────────────────────────
  it('CRIT-2: detail pages render 2 breadcrumb items with clickable parent link', async () => {
    const router = makeRouter({
      titleKey: 'processDefinition',
      parentTitleKey: 'processDefinitions',
      parentTo: { name: 'process-definitions' },
    })
    const wrapper = await mountOn(router, '/processes/definitions/abc-123')

    const nav = wrapper.find('nav')
    expect(nav.exists()).toBe(true)

    const items = wrapper.findAll('li')
    expect(items).toHaveLength(2)

    // First item: clickable link, resolved from the locale key
    const link = items[0].find('a')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('Process Definitions')
    expect(link.attributes('href')).toContain('/processes/definitions')

    // Second item: current page (plain text), resolved from the locale key
    const span = items[1].find('span')
    expect(span.exists()).toBe(true)
    expect(span.text()).toBe('Process Definition')
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 3: all 6 detail-rovers work (via locale keys)
  // ─────────────────────────────────────────────────────────────
  const DETAIL_ROUTES = [
    { titleKey: 'processDefinition', parentTitleKey: 'processDefinitions', parentTo: { name: 'process-definitions' }, parentPath: '/processes/definitions', childPath: '/processes/definitions/1' },
    { titleKey: 'processInstance', parentTitleKey: 'processInstances', parentTo: { name: 'process-instances' }, parentPath: '/processes/instances', childPath: '/processes/instances/1' },
    { titleKey: 'task', parentTitleKey: 'myTasks', parentTo: { name: 'my-tasks' }, parentPath: '/tasks', childPath: '/tasks/1' },
    { titleKey: 'serviceTask', parentTitleKey: 'serviceTasks', parentTo: { name: 'service-tasks' }, parentPath: '/service-tasks', childPath: '/service-tasks/1' },
    { titleKey: 'incident', parentTitleKey: 'incidents', parentTo: { name: 'incidents' }, parentPath: '/incidents', childPath: '/incidents/1' },
  ]

  for (const route of DETAIL_ROUTES) {
    it(`CRIT-3: ${route.titleKey} detail → parent link "${route.parentTitleKey}"`, async () => {
      const router = createRouter({
        history: createMemoryHistory('/ui/'),
        routes: [
          {
            path: '/',
            component: { template: '<router-view />' },
            children: [
              { path: route.parentPath.slice(1), name: route.parentTo.name as string, component: { template: '<div>List</div>' } },
              { path: route.childPath.slice(1), name: `${route.titleKey}-detail`, component: { template: '<div />' }, meta: route },
            ],
          },
        ],
      })
      await router.push(route.childPath)
      await router.isReady()

      const wrapper = mount(BreadcrumbNav, {
        global: { plugins: [router, createPinia(), i18n] },
      })

      expect(wrapper.find('nav').exists()).toBe(true)
      const items = wrapper.findAll('li')
      expect(items).toHaveLength(2)
      expect(items[0].find('a').text()).toBe(en[route.parentTitleKey as keyof typeof en])
    })
  }

  // ─────────────────────────────────────────────────────────────
  // Regression: no parentTitleKey → panel hidden (list-rovers)
  // ─────────────────────────────────────────────────────────────
  it('REGRESSION: routes without parentTitleKey hide breadcrumb panel', async () => {
    const router = makeRouter({ titleKey: 'dashboard' })
    const wrapper = await mountOn(router, '/')

    expect(wrapper.find('nav').exists()).toBe(false)
  })

  // ─────────────────────────────────────────────────────────────
  // WO-ACL-10 criterion 18: labels are locale keys — the kz/ru translation
  // shows instead of the English literal
  // ─────────────────────────────────────────────────────────────
  it('criterion 18: breadcrumb labels resolve through t() — kz locale shows the Kazakh translation', async () => {
    const router = makeRouter({
      titleKey: 'processDefinition',
      parentTitleKey: 'processDefinitions',
      parentTo: { name: 'process-definitions' },
    })
    await router.push('/processes/definitions/abc-123')
    await router.isReady()

    const kzI18n = createI18n({ legacy: false, locale: 'kz', fallbackLocale: 'en', messages: { en, ru, kz } })
    const wrapper = mount(BreadcrumbNav, {
      global: { plugins: [router, createPinia(), kzI18n] },
    })

    const items = wrapper.findAll('li')
    expect(items).toHaveLength(2)
    expect(items[0].find('a').text()).toBe(kz.processDefinitions)
    expect(items[1].find('span').text()).toBe(kz.processDefinition)
    // no raw English literal in the DOM
    expect(wrapper.text()).not.toContain('Process Definitions')
  })

  // ─────────────────────────────────────────────────────────────
  // WO-ACL-10 criterion 19: the injected processName is reactive — the last
  // crumb shows it and updates when it changes
  // ─────────────────────────────────────────────────────────────
  it('criterion 19: the last crumb shows the provided process name', async () => {
    const router = makeRouter({
      titleKey: 'processDefinition',
      parentTitleKey: 'processDefinitions',
      parentTo: { name: 'process-definitions' },
    })
    await router.push('/processes/definitions/abc-123')
    await router.isReady()

    const processName = ref<string | null>('My Awesome Process')
    const wrapper = mount(BreadcrumbNav, {
      global: {
        plugins: [router, createPinia(), i18n],
        provide: { processName },
      },
    })

    const items = wrapper.findAll('li')
    expect(items).toHaveLength(2)
    expect(items[1].find('span').text()).toBe('My Awesome Process')
  })

  it('criterion 19: the crumb reacts when the process name ref changes', async () => {
    const router = makeRouter({
      titleKey: 'processDefinition',
      parentTitleKey: 'processDefinitions',
      parentTo: { name: 'process-definitions' },
    })
    await router.push('/processes/definitions/abc-123')
    await router.isReady()

    const processName = ref<string | null>('v1 name')
    const wrapper = mount(BreadcrumbNav, {
      global: {
        plugins: [router, createPinia(), i18n],
        provide: { processName },
      },
    })
    expect(wrapper.findAll('li')[1].find('span').text()).toBe('v1 name')

    // the detail page loads another process version → name changes
    processName.value = 'v2 name'
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('li')[1].find('span').text()).toBe('v2 name')
  })
})