/**
 * WO-UI-30 часть A — стартовое окно: фиксированная ширина min(94vw,1100px).
 *
 * Контекст: UI-27 ввёл тумблер Уже/Шире (`w-[min(94vw,1280px)]` /
 * `w-[min(94vw,640px)]`, память `zbpm-start-dialog-wide`), но на десктопе он
 * ничего не менял, а потолок не применялся: базовый shadcn
 * DialogScrollContent несёт `md:w-full`, который в Tailwind-каскаде
 * перебивает обычный `w-[...]` на ≥768px (twMerge их не конфликтует),
 * и диалог растягивался до max-w=95vw (~2430px на окне ~2530).
 * Владелец: «кнопку вообще убрать», ширина — min(94vw,1100px).
 *
 * Регресс-гейт (реальный Chromium, страница определения):
 * - модалка в портале (`[role="dialog"]`), класс ровно min(94vw,1100px)
 *   в обычном И md-варианте (иначе md:w-full базы снова перебьёт);
 * - замер getBoundingClientRect().width на 2560/1920/1280/360:
 *   ≈1100 / ≈1100 / ≈1100 / ≈94vw, диалог отцентрован;
 * - тумблера Уже/Шире нет, localStorage-ключ не пишется.
 * Мутация: вернуть w-[min(94vw,1280px)] без md: → красный на 2560
 * (95vw=2432 вместо 1100); вернуть кнопку → красный (текст найден).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessDefinitionDetail from '@/pages/processes/ProcessDefinitionDetail.vue'
import '@/style.css'

const mockStartInstance = vi.hoisted(() => vi.fn().mockResolvedValue({ id: 'inst-1' }))

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { id: 'def1' },
    path: '/processes/definitions/def1',
    fullPath: '/processes/definitions/def1',
    name: 'process-definition-detail',
  }),
  useRouter: () => ({ push: vi.fn() }),
}))

// Ключи как есть: тумблер ищем по ключу, не по переводу.
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/services/processService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/processService')>()),
  getProcessDefinitionXml: vi.fn().mockResolvedValue(null),
}))

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
}))

vi.mock('@/services/formService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/formService')>()),
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'test-proc', version: 1, elements: [] }),
  listForms: vi.fn().mockResolvedValue([]),
}))

vi.mock('@/services/adminService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/adminService')>()),
  listMembers: vi.fn().mockResolvedValue([]),
}))

vi.mock('@/services/presetService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/presetService')>()),
  listPresets: vi.fn().mockResolvedValue([]),
}))

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', sha256: 'abc', createdAt: '2026-01-01', startFormKey: null },
    currentStructure: { id: 'def1', key: 'test-proc', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] },
    currentVersions: [],
    loading: false,
    error: null,
    fetchDefinition: vi.fn(),
    fetchStructure: vi.fn(),
    fetchVersions: vi.fn(),
    startInstance: mockStartInstance,
  }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u1', username: 'op' }, isSuperAdmin: true }),
}))

vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}))

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

function mountPage() {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  host.style.width = '1280px'
  host.style.height = '720px'
  document.body.appendChild(host)
  hosts.push(host)
  // Без stubs.teleport (см. UI-26): портал — в document.body.
  const wrapper = mount(ProcessDefinitionDetail, {
    attachTo: host,
    global: { plugins: [createPinia()] },
  })
  mounted.push(wrapper)
  return wrapper
}

function dialog(): HTMLElement | null {
  return document.querySelector('[role="dialog"]') as HTMLElement | null
}

async function settle() {
  await flushPromises()
  await new Promise((r) => setTimeout(r, 300))
  await flushPromises()
}

async function openDialogAndMeasure(viewportWidth: number): Promise<{ width: number; left: number; right: number }> {
  const page = (await import('vitest/browser')).page
  await page.viewport(viewportWidth, 800)
  const wrapper = mountPage()
  await settle()
  const vm = wrapper.vm as unknown as { showStartModal: boolean }
  vm.showStartModal = true
  await settle()
  const dlg = dialog()
  expect(dlg, `start dialog renders in portal @${viewportWidth}`).not.toBeNull()
  const rect = dlg!.getBoundingClientRect()
  wrapper.unmount()
  mounted = mounted.filter((w) => w !== wrapper)
  return { width: rect.width, left: rect.left, right: rect.right }
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

describe('WO-UI-30 part A: start dialog fixed width (browser)', () => {
  it.each([
    { viewport: 2560, expected: 1100 },
    { viewport: 1920, expected: 1100 },
    { viewport: 1280, expected: 1100 },
    { viewport: 360, expected: 0.94 * 360 },
  ])('ширина ≈ min(94vw,1100px) на вьюпорте $viewport', async ({ viewport, expected }) => {
    const page = (await import('vitest/browser')).page
    try {
      const m = await openDialogAndMeasure(viewport)
      // ±4px: субпиксели, скроллбар оверлея, округления Chromium.
      expect(m.width).toBeGreaterThanOrEqual(expected - 4)
      expect(m.width).toBeLessThanOrEqual(expected + 4)
      // Диалог отцентрован: поля слева/справа симметричны (±8px).
      const leftGap = m.left
      const rightGap = viewport - m.right
      expect(Math.abs(leftGap - rightGap)).toBeLessThanOrEqual(8)
    } finally {
      await page.viewport(1280, 800)
    }
  }, 60000)

  it('тумблера Уже/Шире нет, localStorage-ключ не пишется', async () => {
    const page = (await import('vitest/browser')).page
    await page.viewport(1280, 800)
    try {
      const wrapper = mountPage()
      await settle()
      const vm = wrapper.vm as unknown as { showStartModal: boolean }
      vm.showStartModal = true
      await settle()
      const dlg = dialog()
      expect(dlg, 'start dialog renders in portal').not.toBeNull()
      // Класс — ровно новый контракт, включая md-вариант.
      expect(dlg!.className).toContain('w-[min(94vw,1100px)]')
      expect(dlg!.className).toContain('md:w-[min(94vw,1100px)]')
      expect(dlg!.className).not.toContain('1280px')
      expect(dlg!.className).not.toContain('640px')
      const texts = [...document.querySelectorAll('button')].map((b) => b.textContent ?? '')
      expect(texts.some((x) => x.includes('presetDialogNarrower') || x.includes('presetDialogWider'))).toBe(false)
      expect(localStorage.getItem('zbpm-start-dialog-wide')).toBeNull()
      wrapper.unmount()
      mounted = []
    } finally {
      await page.viewport(1280, 800)
    }
  }, 60000)
})
