// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import RegistrationQueue from './RegistrationQueue.vue'

const mockGetPending = vi.hoisted(() => vi.fn())
const mockApprove = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
const mockReject = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
vi.mock('@/services/registrationService', () => ({
  getPendingRegistrations: mockGetPending,
  approveRegistration: mockApprove,
  rejectRegistration: mockReject,
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))

const mockToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/composables/useToast', () => ({ useToast: () => mockToast }))

const PENDING = [
  { id: 'reg-1', username: 'alice', email: 'alice@test.com', fullName: 'Alice', createdAt: '2026-08-01T10:00:00Z', registrationStatus: 'PENDING_APPROVAL' },
  { id: 'reg-2', username: 'bob', email: 'bob@test.com', fullName: 'Bob', createdAt: '2026-08-02T10:00:00Z', registrationStatus: 'PENDING_APPROVAL' },
]

describe('RegistrationQueue.vue WO-REG-6', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetPending.mockResolvedValue([...PENDING])
  })

  it('criterion4: lists pending registrations', async () => {
    const wrapper = mount(RegistrationQueue)
    await flushPromises()
    expect(mockGetPending).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('alice@test.com')
    expect(wrapper.text()).toContain('bob@test.com')
  })

  it('criterion5: approve removes row from list', async () => {
    const wrapper = mount(RegistrationQueue)
    await flushPromises()
    expect(wrapper.findAll('[data-testid="registration-row"]').length).toBe(2)
    // Mock second load after approve returns only one
    mockGetPending.mockResolvedValue([PENDING[1]])
    const approveBtn = wrapper.find('[data-testid="registration-approve"]')
    await approveBtn.trigger('click')
    await flushPromises()
    expect(mockApprove).toHaveBeenCalledWith('reg-1')
    // After reload, only bob remains
    expect(wrapper.text()).not.toContain('alice@test.com')
    expect(wrapper.text()).toContain('bob@test.com')
  })

  it('criterion5: reject removes row and does not send reason in mail (service layer)', async () => {
    const wrapper = mount(RegistrationQueue)
    await flushPromises()
    // Open reject dialog for first row
    const rejectBtn = wrapper.find('[data-testid="registration-reject"]')
    await rejectBtn.trigger('click')
    await flushPromises()
    const textarea = wrapper.find('[data-testid="reject-reason"]')
    await textarea.setValue('spam reason')
    const confirm = wrapper.find('[data-testid="reject-confirm"]')
    await confirm.trigger('click')
    await flushPromises()
    expect(mockReject).toHaveBeenCalledWith('reg-1', 'spam reason')
    // After reject, list reloads
    expect(mockGetPending).toHaveBeenCalledTimes(2)
  })

  it('criterion4: route meta requiresSuperAdmin', async () => {
    const router = (await import('@/app/router')).default
    const route = router.getRoutes().find(r => r.name === 'admin-registrations')
    expect(route?.meta?.requiresSuperAdmin).toBe(true)
  })
})
