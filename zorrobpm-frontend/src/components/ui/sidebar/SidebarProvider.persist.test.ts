// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import SidebarProvider from '@/components/ui/sidebar/SidebarProvider.vue'
import { SIDEBAR_COOKIE_NAME, useSidebar } from '@/components/ui/sidebar/utils'

// Mock only TooltipProvider — keep createContext and the rest of reka-ui real,
// otherwise utils.ts createContext() call at module level breaks.
vi.mock('reka-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('reka-ui')>()
  return {
    ...actual,
    TooltipProvider: { template: '<div><slot /></div>' },
  }
})

// Consumer that exposes the real sidebar state provided by SidebarProvider —
// proves the value flows through the actual provide/inject path (G-N).
const StateProbe = defineComponent({
  setup() {
    const { open } = useSidebar()
    return () => h('span', { 'data-testid': 'open-state' }, String(open.value))
  },
})

function mountProvider() {
  return mount(SidebarProvider, {
    slots: { default: () => h(StateProbe) },
  })
}

function readOpen(wrapper: ReturnType<typeof mountProvider>): boolean {
  return wrapper.find('[data-testid="open-state"]').text() === 'true'
}

describe('SidebarProvider cookie persist', () => {
  beforeEach(() => {
    // Clear cookie
    document.cookie = `${SIDEBAR_COOKIE_NAME}=; path=/; max-age=0`
  })

  it('reads initial state from cookie: collapsed cookie → provider opens collapsed', () => {
    document.cookie = `${SIDEBAR_COOKIE_NAME}=false; path=/; max-age=3600`

    const wrapper = mountProvider()
    expect(readOpen(wrapper)).toBe(false)
    wrapper.unmount()

    // Remount with the SAME stored state (the WO persist scenario):
    // the restored instance must come back collapsed too.
    const remounted = mountProvider()
    expect(readOpen(remounted)).toBe(false)
    remounted.unmount()
  })

  it('reads initial state from cookie: expanded cookie → provider opens expanded', () => {
    document.cookie = `${SIDEBAR_COOKIE_NAME}=true; path=/; max-age=3600`

    const wrapper = mountProvider()
    expect(readOpen(wrapper)).toBe(true)
    wrapper.unmount()
  })

  it('writes cookie when toggling sidebar', async () => {
    document.cookie = `${SIDEBAR_COOKIE_NAME}=true; path=/; max-age=3600`

    const wrapper = mountProvider()

    // Toggle sidebar: useEventListener listens on window, so dispatch there
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'b', ctrlKey: true }))
    await nextTick()

    // In-memory state flipped AND persisted to the cookie
    expect(readOpen(wrapper)).toBe(false)
    expect(document.cookie).toContain(`${SIDEBAR_COOKIE_NAME}=false`)

    // The next visitor (remount) restores the toggled state from that cookie
    wrapper.unmount()
    const remounted = mountProvider()
    expect(readOpen(remounted)).toBe(false)
    remounted.unmount()
  })

  it('defaults to expanded when no cookie exists', () => {
    // No cookie set
    const wrapper = mountProvider()
    expect(readOpen(wrapper)).toBe(true)
    wrapper.unmount()
  })
})
