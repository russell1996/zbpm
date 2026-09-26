// @vitest-environment jsdom
/**
 * WO-UI-21: instance filter by process — dropdown of deployed definitions,
 * not a free-text input.
 *
 * - Options come from fetchDefinitions(): name + code in every option,
 *   deduped by key (latest version is enough — the backend filter matches
 *   ANY version, ProcessInstanceRepository.byProcessDefinitionKey).
 * - Selecting an option filters instances by its code, identically to the
 *   old text input with the same value.
 * - "All" resets the filter; changing the filter resets pagination.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProcessInstanceList from './ProcessInstanceList.vue'

// jsdom lacks Pointer Capture API — reka's SelectTrigger calls it on
// pointerdown (same polyfill as UserList.test.ts).
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}

const mocks = vi.hoisted(() => ({
  fetchInstances: vi.fn(),
  fetchDefinitions: vi.fn(),
  resetPage: vi.fn(),
  page: { value: 0 },
}))

const DEFINITIONS_FIXTURE = {
  data: [
    { id: 'd1', key: 'vacation', name: 'Vacation Leave', version: 1, sha256: 'a', createdAt: '2026-01-01', startFormKey: null },
    { id: 'd2', key: 'vacation', name: 'Vacation Leave', version: 2, sha256: 'b', createdAt: '2026-02-01', startFormKey: null },
    { id: 'd3', key: 'onboarding', name: null, version: 1, sha256: 'c', createdAt: '2026-01-05', startFormKey: null },
  ],
  totalElements: 3,
}

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    instances: null,
    definitions: DEFINITIONS_FIXTURE,
    loading: false,
    error: null,
    fetchInstances: mocks.fetchInstances,
    fetchDefinitions: mocks.fetchDefinitions,
  }),
}))

vi.mock('@/composables/usePagination', () => ({
  usePagination: () => ({
    page: mocks.page,
    pageSize: 20,
    nextPage: vi.fn(),
    prevPage: vi.fn(),
    hasNext: false,
    hasPrev: false,
    resetPage: mocks.resetPage,
  }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
}))

vi.mock('@/shared/lib/export', () => ({
  exportToCsv: vi.fn(),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'processes/instances', name: 'process-instances', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

// reka Select opens on a left-button pointerdown of the trigger; the menu
// content is in document, so items are queried from document, not wrapper.
// (Same pattern as UserList.test.ts openSelect/chooseSelectItem.)
async function nextTickFlush() {
  await new Promise((r) => setTimeout(r, 0))
}

async function openSelect(wrapper: ReturnType<typeof mount>, triggerTestid: string) {
  const trigger = wrapper.find(`[data-testid="${triggerTestid}"]`)
  expect(trigger.exists()).toBe(true)
  trigger.element.dispatchEvent(
    new MouseEvent('pointerdown', { button: 0, ctrlKey: false, bubbles: true, cancelable: true }),
  )
  await nextTickFlush()
}

async function chooseSelectItem(itemTestid: string) {
  const item = document.querySelector(`[data-testid="${itemTestid}"]`)
  expect(item).not.toBeNull()
  ;(item as HTMLElement).dispatchEvent(new MouseEvent('pointerup', { bubbles: true, button: 0 }))
  await nextTickFlush()
}

describe('WO-UI-21: ProcessInstanceList definition dropdown filter', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mocks.page.value = 0
    mocks.fetchInstances.mockResolvedValue({ data: [], totalElements: 0 })
    mocks.fetchDefinitions.mockResolvedValue(DEFINITIONS_FIXTURE)
    // Real timers (UserList.test.ts pattern): the debounce window (250ms)
    // is awaited via vi.waitFor, and reka's helpers rely on setTimeout.
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  /** Awaits the debounced filter reload (WO-UI-18 part B, 250ms window). */
  async function awaitLastInstancesCall() {
    await vi.waitFor(
      () => {
        expect(mocks.fetchInstances.mock.calls.length).toBeGreaterThan(0)
      },
      { timeout: 3000 },
    )
    await flushPromises()
    const calls = mocks.fetchInstances.mock.calls
    return calls[calls.length - 1][0]
  }

  async function mountList() {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()
    const wrapper = mount(ProcessInstanceList, {
      global: { plugins: [router, createPinia()] },
    })
    await flushPromises()
    return wrapper
  }

  // ─────────────────────────────────────────────────────────────
  // WO criterion 1: dropdown shows real deployed processes —
  // name AND code in every option, deduped by key
  // ─────────────────────────────────────────────────────────────
  it('CRIT-1: options show name + code, deduped by key', async () => {
    const wrapper = await mountList()

    await openSelect(wrapper, 'definition-filter')
    const items = [...document.querySelectorAll('[data-testid^="definition-filter-"]')]
    const labels = items.map((el) => el.textContent)

    // Name AND code in the vacation option (owner explicitly asked for both)
    expect(labels.some((t) => t?.includes('Vacation Leave') && t?.includes('vacation'))).toBe(true)
    // Two vacation versions in the fixture → exactly ONE vacation option
    expect(labels.filter((t) => t?.includes('vacation')).length).toBe(1)
    // Nameless definition falls back to bare code
    expect(labels.some((t) => t?.includes('onboarding'))).toBe(true)
    // "All" reset option is present
    expect(document.querySelector('[data-testid="definition-filter-ALL"]')).not.toBeNull()
  })

  // ─────────────────────────────────────────────────────────────
  // WO criterion 2: selecting a process filters instances by its code
  // ─────────────────────────────────────────────────────────────
  it('CRIT-2: selecting a process filters instances by its code', async () => {
    const wrapper = await mountList()
    mocks.fetchInstances.mockClear()

    await openSelect(wrapper, 'definition-filter')
    await chooseSelectItem('definition-filter-vacation')
    // Debounced watch like the old text input had (WO-UI-18 part B)
    const lastCall = await awaitLastInstancesCall()

    expect(lastCall.processDefinitionKey).toBe('vacation')
    expect(lastCall.processDefinitionId).toBeUndefined()
  })

  // ─────────────────────────────────────────────────────────────
  // WO criterion 3: empty ("All") choice resets the filter
  // ─────────────────────────────────────────────────────────────
  it('CRIT-3: "All" resets the filter, shows all instances', async () => {
    const wrapper = await mountList()

    // First narrow down…
    await openSelect(wrapper, 'definition-filter')
    await chooseSelectItem('definition-filter-vacation')
    await awaitLastInstancesCall()
    // …then reset to All
    mocks.fetchInstances.mockClear()
    await openSelect(wrapper, 'definition-filter')
    await chooseSelectItem('definition-filter-ALL')
    const lastCall = await awaitLastInstancesCall()

    expect(lastCall.processDefinitionKey).toBeUndefined()
  })

  // ─────────────────────────────────────────────────────────────
  // WO criterion 4: changing the filter resets pagination
  // ─────────────────────────────────────────────────────────────
  it('CRIT-4: changing the filter resets the page', async () => {
    const wrapper = await mountList()
    mocks.page.value = 2
    mocks.resetPage.mockClear()
    mocks.fetchInstances.mockClear()

    await openSelect(wrapper, 'definition-filter')
    await chooseSelectItem('definition-filter-vacation')
    await awaitLastInstancesCall()

    expect(mocks.resetPage).toHaveBeenCalled()
  })

  // ─────────────────────────────────────────────────────────────
  // WO task 5: definitions loaded once on mount (latest-only),
  // not re-fetched on every filter/pagination change
  // ─────────────────────────────────────────────────────────────
  it('TASK-5: definitions fetched once on mount with latestVersionOnly', async () => {
    const wrapper = await mountList()

    expect(mocks.fetchDefinitions).toHaveBeenCalledTimes(1)
    expect(mocks.fetchDefinitions).toHaveBeenCalledWith(
      expect.objectContaining({ latestVersionOnly: true }),
    )

    await openSelect(wrapper, 'definition-filter')
    await chooseSelectItem('definition-filter-vacation')
    await awaitLastInstancesCall()

    expect(mocks.fetchDefinitions).toHaveBeenCalledTimes(1)
  })
})
