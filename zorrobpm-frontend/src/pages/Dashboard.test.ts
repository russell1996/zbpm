// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import Dashboard from '@/pages/Dashboard.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('vue-router', () => ({
  useRouter: vi.fn(),
  useRoute: vi.fn(),
}))

const mockToastError = vi.fn()
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    error: mockToastError,
    success: vi.fn(),
  }),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitions: vi.fn().mockRejectedValue(new Error('API error')),
}))

vi.mock('@/services/instanceService', () => ({
  getProcessInstances: vi.fn().mockRejectedValue(new Error('API error')),
}))

vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn().mockRejectedValue(new Error('API error')),
  getServiceTasks: vi.fn().mockRejectedValue(new Error('API error')),
}))

vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockRejectedValue(new Error('API error')),
}))

describe('Dashboard.vue', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    vi.clearAllMocks()
    pinia = setActivePinia(createPinia())
  })

  function mountComponent() {
    return mount(Dashboard, {
      global: { plugins: [pinia] },
    })
  }

  it('shows toast error when API calls fail', async () => {
    mountComponent()
    
    // Wait for onMounted to complete
    await vi.waitFor(() => {
      expect(mockToastError).toHaveBeenCalledWith('loadError')
    })
  })
})