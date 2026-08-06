// @vitest-environment jsdom
/**
 * WO-FE-18: Breadcrumb dedup + back-nav
 *
 * - List-rovers: only 1 breadcrumb item → panel hidden (duplicates <h1>)
 * - Detail-rovers: 2 items, first is clickable link back to list
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import BreadcrumbNav from './BreadcrumbNav.vue'

const routerPush = vi.fn()

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
            meta: { title: 'Process Definitions' },
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

describe('WO-FE-18: BreadcrumbNav', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 1: list-rovers — panel NOT rendered
  // ─────────────────────────────────────────────────────────────
  it('CRIT-1: list pages hide breadcrumb panel (1 item → v-if false)', async () => {
    const router = makeRouter({ title: 'Process Definitions' })
    await router.push('/processes/definitions')
    await router.isReady()

    const wrapper = mount(BreadcrumbNav, {
      global: { plugins: [router, createPinia()] },
    })

    // Panel should NOT be rendered (breadcrumbs.length = 1, v-if="> 1")
    expect(wrapper.find('nav').exists()).toBe(false)
    expect(wrapper.find('ol').exists()).toBe(false)
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 2: detail-rovers — panel shows 2 items, first clickable
  // ─────────────────────────────────────────────────────────────
  it('CRIT-2: detail pages render 2 breadcrumb items with clickable parent link', async () => {
    const router = makeRouter({
      title: 'Process Definition',
      parentTitle: 'Process Definitions',
      parentTo: { name: 'process-definitions' },
    })
    await router.push('/processes/definitions/abc-123')
    await router.isReady()

    const wrapper = mount(BreadcrumbNav, {
      global: { plugins: [router, createPinia()] },
    })

    const nav = wrapper.find('nav')
    expect(nav.exists()).toBe(true)

    const items = wrapper.findAll('li')
    expect(items).toHaveLength(2)

    // First item: clickable link
    const link = items[0].find('a')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('Process Definitions')
    expect(link.attributes('href')).toContain('/processes/definitions')

    // Second item: current page (plain text)
    const span = items[1].find('span')
    expect(span.exists()).toBe(true)
    expect(span.text()).toBe('Process Definition')
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 3: all 6 detail-rovers work
  // ─────────────────────────────────────────────────────────────
  const DETAIL_ROUTES = [
    { title: 'Process Definition', parentTitle: 'Process Definitions', parentTo: { name: 'process-definitions' }, parentPath: '/processes/definitions', childPath: '/processes/definitions/1' },
    { title: 'Process Instance', parentTitle: 'Process Instances', parentTo: { name: 'process-instances' }, parentPath: '/processes/instances', childPath: '/processes/instances/1' },
    { title: 'Task', parentTitle: 'My Tasks', parentTo: { name: 'my-tasks' }, parentPath: '/tasks', childPath: '/tasks/1' },
    { title: 'Service Task', parentTitle: 'Service Tasks', parentTo: { name: 'service-tasks' }, parentPath: '/service-tasks', childPath: '/service-tasks/1' },
    { title: 'Incident', parentTitle: 'Incidents', parentTo: { name: 'incidents' }, parentPath: '/incidents', childPath: '/incidents/1' },
  ]

  for (const route of DETAIL_ROUTES) {
    it(`CRIT-3: ${route.title} detail → parent link "${route.parentTitle}"`, async () => {
      const router = createRouter({
        history: createMemoryHistory('/ui/'),
        routes: [
          {
            path: '/',
            component: { template: '<router-view />' },
            children: [
              { path: route.parentPath.slice(1), name: route.parentTo.name as string, component: { template: '<div>List</div>' } },
              { path: route.childPath.slice(1), name: `${route.title.toLowerCase().replace(/\s/g, '-')}-detail`, component: { template: '<div />' }, meta: route },
            ],
          },
        ],
      })
      await router.push(route.childPath)
      await router.isReady()

      const wrapper = mount(BreadcrumbNav, {
        global: { plugins: [router, createPinia()] },
      })

      expect(wrapper.find('nav').exists()).toBe(true)
      const items = wrapper.findAll('li')
      expect(items).toHaveLength(2)
      expect(items[0].find('a').text()).toBe(route.parentTitle)
    })
  }

  // ─────────────────────────────────────────────────────────────
  // Regression: no parentTitle → panel hidden (list-rovers)
  // ─────────────────────────────────────────────────────────────
  it('REGRESSION: routes without parentTitle hide breadcrumb panel', async () => {
    const router = makeRouter({ title: 'Dashboard' })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(BreadcrumbNav, {
      global: { plugins: [router, createPinia()] },
    })

    expect(wrapper.find('nav').exists()).toBe(false)
  })
})
