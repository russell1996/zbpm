/**
 * WO-UI-26 критерий 7 — окно запуска процесса: проблемы владельца
 * воспроизведены и исправлены (либо доказано, что исправлены VT-3).
 *
 * Жалоба WO (п.6): «узкое окно, прилипающие табы». VT-3 переделал диалог;
 * WO-UI-27 (смержен, `76a4eb7fc`) перевёл стартовую модалку на shadcn-Dialog
 * и расширил контракт: ширина по умолчанию `w-[min(94vw,1280px)]`, тумблер
 * Уже/Шире (`w-[min(94vw,640px)]`, память в localStorage `zbpm-start-dialog-wide`),
 * футер с кнопкой запуска внизу. Таб-переключателя в стартовом окне нет
 * (режим «Таблица|Сырой» живёт внутри VariablesEditor — не предмет гейта).
 *
 * Регресс-гейт на контракт UI-27 (реальный Chromium, страница определения):
 * - модалка рендерится порталом в document (`[role="dialog"]` — НЕ внутри
 *   wrapper: shadcn Dialog идёт через Teleport; стаб teleport глушит reka-
 *   Presence целиком — доказано пробой: со стабом html = 356 симв. коммента,
 *   без стаба диалог в document);
 * - ширина по умолчанию — класс `min(94vw,1280px)`, замер на вьюпорте 960:
 *   94vw ≈ 902, в пределах [560, 960];
 * - тумблер «Уже» сужает до `min(94vw,640px)` и запоминает (`localStorage`);
 * - кнопка запуска видна без прокрутки страницы;
 * - на 360px диалог не шире вьюпорта (нет горизонтального переполнения).
 * Мутация: вернуть width 400px / убрать футер → красный.
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

// Ключи как есть (как в UI-27-тесте): тумблер ищем по ключу, не по переводу.
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

function mountPage(hostWidth = 1280) {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  host.style.width = `${hostWidth}px`
  host.style.height = '720px'
  document.body.appendChild(host)
  hosts.push(host)
  // Без stubs.teleport (см. шапку): портал — в document.body.
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

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.removeItem('zbpm-start-dialog-wide')
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

describe('WO-UI-26 criterion 7: start dialog geometry (browser)', () => {
  it('стартовое окно широкое по умолчанию, тумблер сужает, запуск виден без скролла', async () => {
    const page = (await import('vitest/browser')).page
    await page.viewport(960, 720)
    try {
      const wrapper = mountPage()
      await settle()
      const vm = wrapper.vm as unknown as { showStartModal: boolean }
      vm.showStartModal = true
      await settle()
      const dlg = dialog()
      expect(dlg, 'start dialog renders in portal').not.toBeNull()
      // Контракт UI-27: дефолт — широко.
      expect(dlg!.className).toContain('w-[min(94vw,1280px)]')
      const rect = dlg!.getBoundingClientRect()
      // 94vw от 960 ≈ 902: в пределах [560, 960].
      expect(rect.width).toBeGreaterThanOrEqual(560)
      expect(rect.width).toBeLessThanOrEqual(960)
      // Кнопка запуска видна без прокрутки страницы.
      const start = [...document.querySelectorAll('button')].find((b) =>
        (b.textContent ?? '').includes('startProcess'))
      expect(start, 'start button exists').toBeDefined()
      const startRect = start!.getBoundingClientRect()
      expect(startRect.height).toBeGreaterThan(0)
      expect(startRect.top).toBeGreaterThanOrEqual(0)
      expect(startRect.bottom).toBeLessThanOrEqual(720)
      // Тумблер «Уже» — сужает и запоминает.
      const narrower = [...document.querySelectorAll('button')].find((b) =>
        (b.textContent ?? '').includes('presetDialogNarrower')) as HTMLElement | undefined
      expect(narrower, 'narrower toggle exists').toBeDefined()
      narrower!.click()
      await settle()
      expect(dialog()!.className).toContain('w-[min(94vw,640px)]')
      expect(localStorage.getItem('zbpm-start-dialog-wide')).toBe('0')
      wrapper.unmount()
      mounted = []
    } finally {
      await page.viewport(1280, 720)
    }
  }, 60000)

  it('на 360px стартовое окно не шире вьюпорта (нет горизонтального переполнения)', async () => {
    // vw считается от ОКНА браузера, а не от host-div: сужаем окно.
    const page = (await import('vitest/browser')).page
    await page.viewport(360, 720)
    try {
      const wrapper = mountPage(360)
      await settle()
      const vm = wrapper.vm as unknown as { showStartModal: boolean }
      vm.showStartModal = true
      await settle()
      const dlg = dialog()
      expect(dlg, 'start dialog renders in portal').not.toBeNull()
      const rect = dlg!.getBoundingClientRect()
      // 94vw от 360 = ~338: диалог уже вьюпорта И внутри него (страницу не рвёт).
      // (NB: scrollWidth всей страницы здесь НЕ ассертим: сама страница
      // определения за модалкой имеет непереносимый ряд кнопок шапки/табов —
      // живой зонд: 7 кнопок right>362 и БЕЗ модалки; это не стартовое окно,
      // не критерий 7 — зафиксировано как V7-находка в отчёте WO-UI-26.)
      expect(rect.width).toBeLessThanOrEqual(360)
      expect(rect.width).toBeGreaterThan(200)
      expect(rect.left).toBeGreaterThanOrEqual(-1)
      expect(rect.right).toBeLessThanOrEqual(361)
      wrapper.unmount()
      mounted = []
    } finally {
      await page.viewport(1280, 720)
    }
  }, 60000)
})
