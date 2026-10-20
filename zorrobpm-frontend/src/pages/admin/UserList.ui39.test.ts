// @vitest-environment jsdom
/**
 * WO-UI-39: фильтры списка пользователей живут в query-параметрах маршрута —
 * состояние переживает перезагрузку страницы и не зависит от локального ref.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import UserList from './UserList.vue'

// jsdom lacks Pointer Capture API — reka's SelectTrigger calls it on pointerdown.
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}

const USERS = [
  { id: 'u1', username: 'alice', fullName: 'Alice', email: 'alice@test.com', role: 'ADMIN', active: true, userType: 'HUMAN', createdAt: '', updatedAt: '' },
  { id: 'u2', username: 'bot', fullName: null, email: null, role: 'USER', active: false, userType: 'SYSTEM', createdAt: '', updatedAt: '' },
]

const getUsers = vi.hoisted(() => vi.fn())
vi.mock('@/services/userService', () => ({
  getUsers,
  createUser: vi.fn(),
  updateUser: vi.fn(),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [{ path: 'admin/users', name: 'admin-users', component: UserList }],
      },
    ],
  })
}

async function openSelect(wrapper: ReturnType<typeof mount>, triggerTestid: string) {
  const trigger = wrapper.find(`[data-testid="${triggerTestid}"]`)
  expect(trigger.exists()).toBe(true)
  trigger.element.dispatchEvent(
    new MouseEvent('pointerdown', { button: 0, ctrlKey: false, bubbles: true, cancelable: true }),
  )
  await new Promise((r) => setTimeout(r, 0))
}

async function chooseSelectItem(itemTestid: string) {
  const item = document.querySelector(`[data-testid="${itemTestid}"]`)
  expect(item).not.toBeNull()
  ;(item as HTMLElement).dispatchEvent(new MouseEvent('pointerup', { bubbles: true, button: 0 }))
  await new Promise((r) => setTimeout(r, 0))
}

describe('WO-UI-39: UserList — фильтры в query-параметрах', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
    getUsers.mockResolvedValue({ data: USERS, totalElements: 2 })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  async function mountList(url: string) {
    const router = makeRouter()
    await router.push(url)
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(getUsers.mock.calls.length).toBeGreaterThan(0))
    return { router, wrapper }
  }

  it('CRIT-1: фильтры из URL применяются (прямая ссылка)', async () => {
    const { router, wrapper } = await mountList('/admin/users?users.search=alice&users.type=HUMAN&users.active=ALL')
    const args = getUsers.mock.calls[getUsers.mock.calls.length - 1][0]
    expect(args.username).toBe('alice')
    expect(args.active).toBeUndefined() // ALL = без параметра active
    // Фильтр по типу — клиентский: в таблице только HUMAN-пользователи
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).not.toContain('bot')
    router.currentRoute.value.query['users.type']
    expect(router.currentRoute.value.query['users.type']).toBe('HUMAN')
    wrapper.unmount()
  })

  it('CRIT-2: дефолты не попадают в URL (чистый URL)', async () => {
    const { router, wrapper } = await mountList('/admin/users')
    expect(router.currentRoute.value.query['users.search']).toBeUndefined()
    expect(router.currentRoute.value.query['users.type']).toBeUndefined()
    expect(router.currentRoute.value.query['users.active']).toBeUndefined()
    wrapper.unmount()
  })

  it('CRIT-3: битый параметр из URL → дефолт (страница грузится, не падает)', async () => {
    const { router, wrapper } = await mountList('/admin/users?users.type=ROBOT&users.active=maybe')
    const args = getUsers.mock.calls[getUsers.mock.calls.length - 1][0]
    // ACTIVE (дефолт) → active: true
    expect(args.active).toBe(true)
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['users.type']).toBeUndefined()
      expect(router.currentRoute.value.query['users.active']).toBeUndefined()
    })
    wrapper.unmount()
  })

  it('CRIT-4: смена фильтра пишется в URL', async () => {
    const { router, wrapper } = await mountList('/admin/users')
    await openSelect(wrapper, 'user-type-filter')
    await chooseSelectItem('user-type-filter-SYSTEM')
    await vi.waitFor(() => expect(router.currentRoute.value.query['users.type']).toBe('SYSTEM'))
    wrapper.unmount()
  })
})
