// @vitest-environment jsdom
/**
 * WO-UI-25 критерий 8 — useInstanceViewState: вид живёт в адресе.
 * Настоящий router (memory history): двусторонняя синхронизация,
 * back/forward, deep link, мусор в query, запись через replace.
 */
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent, h, type Ref } from 'vue'
import { useInstanceViewState, type InstanceTabId } from './useInstanceViewState'

type ViewState = {
  tab: Ref<InstanceTabId>
  element: Ref<string | null>
  plane: Ref<string | null>
  activitiesPage: Ref<number>
}

function makeRouter(initialUrl: string): Promise<Router> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/processes/instances/:id', component: defineComponent({ setup: () => () => h('div') }) }],
  })
  return router.push(initialUrl).then(() => router)
}

async function mountState(router: Router) {
  const states: ViewState[] = []
  const host = defineComponent({
    setup() {
      const s: ViewState = useInstanceViewState()
      states.push(s)
      return () => h('div', `${s.tab.value}|${s.element.value}|${s.plane.value}|${s.activitiesPage.value}`)
    },
  })
  const wrapper = mount(host, { global: { plugins: [router] } })
  await flushPromises()
  const state = states[0]
  if (!state) throw new Error('view state not exposed')
  return { wrapper, state }
}

describe('useInstanceViewState (WO-UI-25 criterion 8)', () => {
  it('defaults without query: bpmn tab, no element/plane/page', async () => {
    const router = await makeRouter('/processes/instances/pi-1')
    const { wrapper, state } = await mountState(router)
    expect(state.tab.value).toBe('bpmn')
    expect(state.element.value).toBeNull()
    expect(state.plane.value).toBeNull()
    expect(state.activitiesPage.value).toBe(0)
    // Дефолты в адрес не пишутся — ссылка остаётся чистой.
    expect(router.currentRoute.value.query).toEqual({})
    wrapper.unmount()
  })

  it('deep link initializes the whole view', async () => {
    const router = await makeRouter('/processes/instances/pi-1?tab=history&element=Sub_Task&plane=SubProcess_1&page=2')
    const { wrapper, state } = await mountState(router)
    expect(state.tab.value).toBe('history')
    expect(state.element.value).toBe('Sub_Task')
    expect(state.plane.value).toBe('SubProcess_1')
    expect(state.activitiesPage.value).toBe(2)
    wrapper.unmount()
  })

  it('local change lands in the address via replace (back skips tab clicks)', async () => {
    const router = await makeRouter('/processes/instances/pi-1')
    const { wrapper, state } = await mountState(router)
    state.tab.value = 'history'
    state.element.value = 'Sub_Task'
    await flushPromises()
    const full = router.currentRoute.value.fullPath
    expect(router.currentRoute.value.query).toEqual({ tab: 'history', element: 'Sub_Task' })
    // O-2 (verifier раунд 2): replace, не push — back() это no-op (тот же
    // fullPath+query). При push back откатил бы query к раннему → RED.
    await router.back()
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe(full)
    expect(router.currentRoute.value.query).toEqual({ tab: 'history', element: 'Sub_Task' })
    wrapper.unmount()
  })

  it('back restores the previous view', async () => {
    const router = await makeRouter('/processes/instances/pi-1')
    const { wrapper, state } = await mountState(router)
    state.tab.value = 'tasks'
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('tasks')
    await router.push('/processes/instances/sub-1')
    await flushPromises()
    expect(state.tab.value).toBe('bpmn')
    await router.back()
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('tasks')
    expect(state.tab.value).toBe('tasks')
    wrapper.unmount()
  })

  it('garbage in query falls back to defaults without throwing', async () => {
    const router = await makeRouter('/processes/instances/pi-1?tab=nope&page=-3&element=&plane=')
    const { wrapper, state } = await mountState(router)
    expect(state.tab.value).toBe('bpmn')
    expect(state.activitiesPage.value).toBe(0)
    expect(state.element.value).toBeNull()
    expect(state.plane.value).toBeNull()
    wrapper.unmount()
  })
})
