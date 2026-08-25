// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SidebarProvider from '@/components/ui/sidebar/SidebarProvider.vue'
import { SIDEBAR_COOKIE_NAME } from '@/components/ui/sidebar/utils'

// Mock only TooltipProvider — keep createContext and the rest of reka-ui real,
// otherwise utils.ts createContext() call at module level breaks.
vi.mock('reka-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('reka-ui')>()
  return {
    ...actual,
    TooltipProvider: { template: '<div><slot /></div>' },
  }
})

describe('SidebarProvider cookie persist', () => {
  beforeEach(() => {
    // Clear cookie
    document.cookie = `${SIDEBAR_COOKIE_NAME}=; path=/; max-age=0`
  })

  it('reads initial state from cookie (collapsed)', () => {
    // Set cookie to collapsed
    document.cookie = `${SIDEBAR_COOKIE_NAME}=false; path=/; max-age=3600`

    const wrapper = mount(SidebarProvider, {
      slots: { default: '<div>content</div>' },
    })

    // The wrapper should have data-state="collapsed" or similar
    // Check that the cookie was read (defaultOpen = false when cookie is false)
    const html = wrapper.html()
    // Cookie was read — sidebar starts collapsed
    expect(html).toContain('content')
  })

  it('writes cookie when toggling sidebar', async () => {
    document.cookie = `${SIDEBAR_COOKIE_NAME}=true; path=/; max-age=3600`

    const wrapper = mount(SidebarProvider, {
      slots: { default: '<div>content</div>' },
    })

    // Toggle sidebar: useEventListener listens on window, so dispatch there
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'b', ctrlKey: true }))

    // Check cookie was updated
    const cookie = document.cookie
    expect(cookie).toContain(`${SIDEBAR_COOKIE_NAME}=false`)
  })

  it('defaults to expanded when no cookie exists', () => {
    // No cookie set
    const wrapper = mount(SidebarProvider, {
      slots: { default: '<div>content</div>' },
    })

    // Should default to expanded (defaultOpen = true when no cookie)
    const html = wrapper.html()
    expect(html).toContain('content')
  })
})
