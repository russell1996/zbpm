/**
 * WO-UI-35, критерий (е): на странице инстанса вкладка «Инциденты» —
 * ячейка сообщения с длинным текстом открывает shadcn Dialog целиком.
 *
 * Реальный ProcessInstanceDetail + реальный Chromium + реальный vue-i18n
 * (t()-стаб не может упасть — P-54; паттерн монтирования — из
 * ProcessInstanceDetail.ui23.browser.test.ts, сервисы смocked).
 *
 * Две строки инцидентов: длинное сообщение (хвост-маркер не читается в
 * 220px+ячейке max-w-xs) и короткое.
 *  - длинная: по живому замеру scrollWidth > clientWidth (реальный
 *    клиппинг, не класс), role="button"; клик → диалог с ПОЛНЫМ текстом
 *    (маркер хвоста внутри), заголовок = «Message» (t('message')),
 *    «Копировать» кладёт полный текст в буфер, ОДИН крестик, Esc закрывает
 *    и фокус возвращается на ячейку;
 *  - короткая: без role="button", клик ничего не открывает.
 *
 * Мутации: «только если обрезан» убран → короткая строка кликабелна →
 * красный; Dialog убран (title-только) → длинная не открывает диалог →
 * красный.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'

const holder = vi.hoisted(() => ({
  longMessage:
    'errors: variable with name assigneeEmployeeId is not present in the scope of the element — '
    + 'a long server-side explanation that never fits into the incidents-table cell, '
    + 'tail-marker-UI35-PAGE-4d8e2b',
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u-owner', username: 'alice' }, isSuperAdmin: true }),
}))

vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({ id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [] }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  deployProcessDefinition: vi.fn().mockResolvedValue({}),
  addProcessDefinitionVersion: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/services/taskService', () => ({
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({
    data: [
      {
        id: 'inc-long',
        message: holder.longMessage,
        status: 'OPEN',
        createdAt: '2026-01-01T00:00:00Z',
        completedAt: null,
        processInstanceId: 'pi-1',
        processDefinitionId: 'pd-1',
        processName: 'Test',
        processKey: 'test',
        elementName: 'taskA',
        bpmnElementId: 'taskA',
        activityId: 'act-1',
      },
      {
        id: 'inc-short',
        message: 'short',
        status: 'OPEN',
        createdAt: '2026-01-01T00:00:00Z',
        completedAt: null,
        processInstanceId: 'pi-1',
        processDefinitionId: 'pd-1',
        processName: 'Test',
        processKey: 'test',
        elementName: 'taskB',
        bpmnElementId: 'taskB',
        activityId: 'act-2',
      },
    ],
    totalElements: 2,
    pageIndex: 0,
    pageSize: 100,
  }),
  getIncident: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', startedAt: '2026-01-01', completedAt: null, cancelled: false, processName: 'Test', processKey: 'test', processVersion: 1 }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn(),
  cancelProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1' }),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'order', version: 1, elements: [] }),
  saveElementSchema: vi.fn().mockResolvedValue(undefined),
  getForm: vi.fn().mockResolvedValue(null),
  listForms: vi.fn().mockResolvedValue([]),
  createElementBinding: vi.fn().mockResolvedValue({}),
  deployForm: vi.fn().mockResolvedValue(undefined),
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
  generateSchema: vi.fn().mockResolvedValue('{}'),
}))
vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn().mockResolvedValue({}),
  removeMember: vi.fn().mockResolvedValue({}),
  searchMemberCandidates: vi.fn().mockResolvedValue([]),
  addMember: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

function makeI18n(locale: string) {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    messages: { ru, en, kz },
  })
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

function until(cond: () => boolean, timeout = 8000): Promise<void> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const tick = () => {
      if (cond()) return resolve()
      if (Date.now() - start > timeout) return reject(new Error('timeout waiting for condition'))
      requestAnimationFrame(tick)
    }
    tick()
  })
}

describe('WO-UI-35: incidents tab — truncated message opens the full-text dialog (browser)', () => {
  it('long incident message: clipped cell is clickable, dialog carries the FULL text + copy; short row stays plain', async () => {
    const i18n = makeI18n('en')
    const host = document.createElement('div')
    host.style.position = 'absolute'
    host.style.top = '0'
    host.style.left = '0'
    host.style.display = 'flex'
    host.style.width = '1200px'
    host.style.height = '800px'
    document.body.appendChild(host)
    hosts.push(host)

    // Без teleport-стаба: река-диалог порталится в реальный body ровно как
    // в проде (стаб Teleport ломает рендер DialogContent — проверено прогоном).
    const wrapper = mount(ProcessInstanceDetail, {
      attachTo: host,
      global: { plugins: [createPinia(), i18n] },
    })
    mounted.push(wrapper)
    await flushPromises()

    // Вкладка «Инциденты» (тот же путь, что у владельца со скриншота).
    const vm = wrapper.vm as unknown as { activeTab: string }
    vm.activeTab = 'incidents'
    await flushPromises()
    await until(() => wrapper.findAll('[data-testid="truncated-text"]').length >= 2, 10000)

    // Строка 1 — длинное сообщение: живая клетка max-w-xs реально клиппит.
    // Ищем по маркеру хвоста: на странице есть и другие TruncatedText
    // (имя инстанса в шапке), position-индекс недостоверен.
    const allCells = wrapper.findAll('[data-testid="truncated-text"]')
    const longCell = allCells
      .map((c) => c.element as HTMLElement)
      .find((el) => (el.textContent ?? '').includes('tail-marker-UI35-PAGE-4d8e2b'))
    expect(longCell, 'long incident message cell rendered').toBeTruthy()
    const cell = longCell as HTMLElement
    const longCs = getComputedStyle(cell)
    expect(longCs.textOverflow).toBe('ellipsis')
    expect(cell.scrollWidth).toBeGreaterThan(cell.clientWidth)
    expect(cell.getAttribute('role')).toBe('button')

    // Клик по ячейке → shadcn Dialog с ПОЛНЫМ текстом.
    cell.click()
    await until(() => document.querySelector('[role="dialog"]') !== null, 8000)
    const dialog = document.querySelector('[role="dialog"]') as HTMLElement
    expect(dialog.textContent).toContain('tail-marker-UI35-PAGE-4d8e2b')
    // Заголовок = название колонки «Message».
    expect(dialog.querySelector('[data-testid="truncated-text-title"]')?.textContent).toBe(en.message)
    // Кнопка «Копировать» (shadcn) кладёт ПОЛНЫЙ текст в буфер.
    const copyBtn = dialog.querySelector('[data-testid="truncated-text-copy"]') as HTMLButtonElement
    expect(copyBtn, 'copy button in the dialog').toBeTruthy()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    copyBtn.click()
    await flushPromises()
    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText).toHaveBeenCalledWith(holder.longMessage)

    // Одно закрытие: крестик shadcn; кнопки «Close» в футере нет.
    const buttons = [...dialog.querySelectorAll('button')]
    expect(
      buttons.filter((b) => (b.textContent ?? '').trim().toLowerCase() === 'close'),
    ).toHaveLength(1)
    expect(buttons.find((b) => b.getAttribute('data-testid') === 'truncated-text-copy')).toBeTruthy()

    // Esc → закрытие + фокус на ячейке.
    cell.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await until(() => document.querySelector('[role="dialog"]') === null, 8000)
    await until(() => document.activeElement === cell, 4000)

    // Строка 2 — короткое сообщение: без роли button, клик ничего не открывает.
    const shortCell = allCells
      .map((c) => c.element as HTMLElement)
      .find((el) => (el.textContent ?? '').trim() === 'short') as HTMLElement
    expect(shortCell, 'short incident message cell rendered').toBeTruthy()
    expect(shortCell.getAttribute('role'), 'short incident message is NOT clickable').toBeNull()
    expect(shortCell.getAttribute('tabindex')).toBeNull()
    shortCell.click()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 300))
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  }, 120000)
})
