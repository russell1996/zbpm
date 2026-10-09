/**
 * WO-VT-3 СТАДИЯ 0: самостоятельный UX-аудит поверхностей шаблонов в живом
 * Chromium. Каждый тест — измеримое утверждение из брифа (критерии 1–8, 11):
 * сейчас они КРАСНЫЕ (RED-замеры стадии 0/1), после переделки — ЗЕЛЁНЫЕ
 * (регрессия). Скриншоты «ДО» — governance/reports/assets/vt3/.
 *
 * Замоканы только сетевые сервисы; i18n настоящий; тёмная тема — классом
 * .dark на documentElement (как ui-store).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { page } from 'vitest/browser'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import VariablesEditor from '@/widgets/presets/VariablesEditor.vue'
import PresetPicker from '@/widgets/presets/PresetPicker.vue'
import PresetEditorDialog from '@/widgets/presets/PresetEditorDialog.vue'
import ElementPresetsPanel from '@/widgets/presets/ElementPresetsPanel.vue'
import PresetManagerPanel from '@/widgets/presets/PresetManagerPanel.vue'
import InstanceMessagePanel from '@/widgets/presets/InstanceMessagePanel.vue'
import InstanceSnapshotPanel from '@/widgets/presets/InstanceSnapshotPanel.vue'
import PageInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import type { PresetVariable, VariablePreset } from '@/types/presets'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'
import '@/style.css'

vi.mock('@/services/presetService', () => ({
  listPresets: vi.fn().mockResolvedValue([]),
  getPreset: vi.fn(),
  createPreset: vi.fn().mockResolvedValue({ id: 'p1' }),
  updatePreset: vi.fn(),
  deletePreset: vi.fn().mockResolvedValue(undefined),
  importPreset: vi.fn(),
  exportPreset: vi.fn().mockResolvedValue({}),
  getPresetHistory: vi.fn().mockResolvedValue([]),
  setPresetFavorite: vi.fn(),
  changePresetVisibility: vi.fn(),
  isPresetsDisabled: () => false,
  isPresetConflict: () => false,
  presetErrorCode: () => null,
}))
vi.mock('@/services/formService', () => ({
  getStartForm: vi.fn().mockResolvedValue({ type: 'none' }),
  getTaskForm: vi.fn().mockResolvedValue({ type: 'none' }),
}))
vi.mock('@/services/instanceService', () => ({
  startProcessInstance: vi.fn().mockResolvedValue({ id: 'inst-1' }),
  getProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1', processDefinitionId: 'pd-1', processKey: 'test', processVersion: 1, startedAt: '2026-01-01', completedAt: null }),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  cancelProcessInstance: vi.fn().mockResolvedValue({ id: 'pi-1' }),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/taskService', () => ({
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getUserTask: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/incidentService', () => ({
  resolveIncident: vi.fn().mockResolvedValue(undefined),
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  getIncident: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/messagePublishService', () => ({
  publishMessage: vi.fn().mockResolvedValue({}),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { key: 'orderProcess' }, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

import { listPresets } from '@/services/presetService'

// Скриншоты пишутся в соседний shots/ (Vite server.fs запрещает писать за
// пределы frontend-рута); после прогона артефакты ПЕРЕНОСЯТСЯ в
// governance/reports/assets/vt3/ вручную. Префикс vt3- — чтобы не смешивать
// с чужими shots.
const SHOT_DIR = 'shots'
const SHOT = (name: string) => `${SHOT_DIR}/vt3-${name}.png`

// --- фикстуры (критерий 2) -------------------------------------------------
const FIVE: PresetVariable[] = [
  { name: 'createdEmployeeId', type: 'LONG', value: '21346' },
  { name: 'processTypeId', type: 'LONG', value: '1' },
  { name: 'sourceUuid', type: 'UUID', value: '01a0b481-3ff1-7334-82e0-9c1e5b2a3d4f' },
  { name: 'sourceVersion', type: 'LONG', value: '1' },
  {
    name: 'stages',
    type: 'JSON',
    value: JSON.stringify([
      { stageSequence: 1, employeeFirstName: 'Кирилл', employeeMiddleName: 'Игоревич' },
      { stageSequence: 2, employeeFirstName: 'Анна', employeeMiddleName: 'Петровна' },
    ]),
  },
]
const ONE: PresetVariable[] = [{ name: 'onlyVar', type: 'STRING', value: 'hello' }]
const FORTY: PresetVariable[] = Array.from({ length: 40 }, (_, i) => ({
  name: `var${String(i).padStart(2, '0')}_name`,
  type: (['STRING', 'LONG', 'DOUBLE', 'BOOLEAN', 'UUID', 'JSON'] as const)[i % 6],
  value: i % 6 === 5 ? '{"k":[1,2,3]}' : i % 6 === 4 ? 'true' : `v${i}`,
}))
const JSON_60K: PresetVariable[] = [
  { name: 'bigPayload', type: 'JSON', value: JSON.stringify({ rows: Array.from({ length: 1500 }, (_, i) => ({ i, s: 'строка-значение-номер-' + i })) }) },
]
const NAME_60: PresetVariable[] = [
  { name: 'x'.repeat(60), type: 'STRING', value: 'v' },
]
const DRAFT: PresetVariable[] = [{ name: '', type: 'STRING', value: '' }]
const BROKEN_JSON: PresetVariable[] = [{ name: 'j', type: 'JSON', value: '{"a":' }]

function longPreset(i: number): VariablePreset {
  return {
    id: `p-${i}`,
    processDefinitionKey: 'orderProcess',
    targetKind: 'START',
    targetRef: null,
    name: `Очень длинное название шаблона номер ${i} которое не влезает`,
    description: null,
    ownerUserId: 'u1',
    visibility: i % 2 ? 'PROCESS' : 'PRIVATE',
    variables: FIVE,
    favorite: false,
    createdAt: '2026-10-09T00:00:00',
    updatedAt: '2026-10-09T00:00:00',
    version: 1,
  }
}

// --- harness ----------------------------------------------------------------
let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  document.documentElement.classList.remove('dark')
  ;(listPresets as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([])
})

afterEach(async () => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
  document.documentElement.classList.remove('dark')
  await page.viewport(1280, 720)
})

function makeI18n(locale: string) {
  return createI18n({ legacy: false, locale, fallbackLocale: 'en', messages: { ru, en, kz } })
}

function attachHost(width: number, dark = false): HTMLElement {
  if (dark) document.documentElement.classList.add('dark')
  const host = document.createElement('div')
  host.style.width = `${width}px`
  host.style.minHeight = '200px'
  host.style.background = dark ? '#0f172a' : '#fff'
  host.style.colorScheme = dark ? 'dark' : 'light'
  document.body.appendChild(host)
  hosts.push(host)
  return host
}

function mountAt(
  comp: unknown,
  props: Record<string, unknown>,
  width: number,
  opts: { dark?: boolean; locale?: string } = {},
) {
  const i18n = makeI18n(opts.locale ?? 'ru')
  const wrapper = mount(comp as object, {
    attachTo: attachHost(width, opts.dark),
    props: props as never,
    global: { plugins: [createPinia(), i18n] },
  }) as unknown as VueWrapper
  mounted.push(wrapper)
  return wrapper
}

/** Элементы внутри root, у которых контент вылезает по горизонтали. */
function overflowing(root: Element): string[] {
  const out: string[] = []
  const els = root.querySelectorAll('*')
  for (const el of els) {
    const h = el as HTMLElement
    // Намеренные скролл-контейнеры: поле JSON, сырой JSON, тело модалки, pre.
    if (h.tagName === 'TEXTAREA' || h.tagName === 'PRE') continue
    // Поля ввода/селекты скроллятся ВНУТРИ по замыслу (длинное значение видно
    // частично — нормально); их дефект — схлопывание бокса, он ловится
    // отдельными тестами (имя ≥160px, JSON ≥8 строк, кнопка внутри карточки).
    if (h.tagName === 'INPUT' || h.tagName === 'SELECT') continue
    // truncate+ellipsis — задуманное поведение; настоящий дефект виден на
    // уровне строки-контейнера (li/div вылезает за панель).
    if (h.classList.contains('truncate')) continue
    // Визуально скрытые подписи (sr-only, 1px): их scrollWidth — текст,
    // который никто не видит; не дефект.
    if (h.className?.toString?.().split(' ').includes('sr-only')) continue
    if (h.getAttribute('data-audit-scroll') === '1') continue
    // Внутренний скролл по замыслу (overflow-x:auto, контейнер влезает в
    // хост — таблица менеджера): содержимое скроллится внутри, страница цела.
    if (getComputedStyle(h).overflowX === 'auto' && h.clientWidth <= (root as HTMLElement).clientWidth + 1) continue
    if (h.scrollWidth > h.clientWidth + 1) {
      const cls = (h.className?.toString?.() ?? '').split(' ').slice(0, 3).join('.')
      out.push(`${h.tagName.toLowerCase()}.${cls} scroll=${h.scrollWidth} client=${h.clientWidth}`)
    }
  }
  return out
}

const UUID_VALUE = '01a0b481-3ff1-7334-82e0-9c1e5b2a3d4f'

describe('WO-VT-3 audit: VariablesEditor', () => {
  it.each([360, 520, 960])('FIVE @%ipx: карточка без горизонтального переполнения', async (w) => {
    mountAt(VariablesEditor, { modelValue: FIVE }, w)
    await flushPromises()
    expect(overflowing(document.body)).toEqual([])
  })

  it.each([360, 520, 960])('FIVE @%ipx: имя UUID-строки не схлопывается (≥160px)', async (w) => {
    mountAt(VariablesEditor, { modelValue: FIVE }, w)
    await flushPromises()
    const names = [...document.querySelectorAll('[id^="pv-name-"]')] as HTMLElement[]
    expect(names.length).toBe(5)
    for (const n of names) {
      expect(n.getBoundingClientRect().width).toBeGreaterThanOrEqual(160)
    }
  })

  it.each([360, 520, 960])('FIVE @%ipx: удаление — крестик × в один клик, без меню и confirm (WO-UI-27)', async (w) => {
    const wrapper = mountAt(VariablesEditor, { modelValue: FIVE }, w)
    await flushPromises()
    // WO-UI-27 доп.1–2: меню ⋯ в карточке НЕТ — удаление крестиком × в один
    // клик, без confirm; undo — тихим тостом.
    const cards = [...document.querySelectorAll('[data-testid="ve-card"]')] as HTMLElement[]
    expect(cards.length).toBe(5)
    const first = cards[0]
    expect(first.querySelector('[data-testid="ve-row-menu"]'), 'no ⋯ menu in card').toBeFalsy()
    const del = first.querySelector('[data-testid="ve-row-delete"]') as HTMLButtonElement
    expect(del, 'row × button exists').toBeTruthy()
    const mc = first.getBoundingClientRect()
    const mb = del.getBoundingClientRect()
    expect(mb.right).toBeLessThanOrEqual(mc.right + 1)
    expect(Math.min(mb.width, mb.height), 'hit area >=32px').toBeGreaterThanOrEqual(32)
    // Один клик — строка удалена (эмит), без плашки подтверждения.
    del.click()
    await flushPromises()
    expect(first.querySelector('[data-testid="ve-delete-confirm"]'), 'no confirm box').toBeFalsy()
    expect(wrapper.emitted('update:modelValue'), 'one click deletes').toBeTruthy()
    wrapper.unmount()
  })

  it('JSON 60КБ @960: поле видно ≥8 строк', async () => {
    expect(JSON_60K[0].value.length).toBeGreaterThan(50 * 1024)
    mountAt(VariablesEditor, { modelValue: JSON_60K }, 960)
    await flushPromises()
    const area = document.querySelector('[id^="pv-value-"]') as HTMLElement
    const lineH = parseFloat(getComputedStyle(area).lineHeight || '20')
    expect(area.getBoundingClientRect().height).toBeGreaterThanOrEqual(lineH * 8)
  })

  it('мелкий JSON @960: min-height держит пол 8 строк (M-b: регресс пола)', async () => {
    // WO-VT-3 HOLD r1 (M-b): 60К-тест меряет выросшее поле (autoGrow до
    // 40vh) и НЕ ловит снятие min-height — этот тест прибивает пол напрямую
    // на мелком значении (высота < 40vh, autoGrow не растёт).
    mountAt(VariablesEditor, { modelValue: [{ name: 'j', type: 'JSON', value: '{"a":1}' }] }, 960)
    await flushPromises()
    const area = document.querySelector('[id^="pv-value-"]') as HTMLElement
    const cs = getComputedStyle(area)
    const lineH = parseFloat(cs.lineHeight || '20')
    // Пол задан CSS min-height (8 строк), а не атрибутом rows.
    expect(parseFloat(cs.minHeight)).toBeGreaterThanOrEqual(lineH * 8 - 1)
    expect(area.getBoundingClientRect().height).toBeGreaterThanOrEqual(lineH * 8 - 1)
  })

  it('имя 60 символов @360: без переполнения и обрезки смысла', async () => {
    mountAt(VariablesEditor, { modelValue: NAME_60 }, 360)
    await flushPromises()
    // Скриншот ДО переделки — снимаем до ассертов, чтобы артефакт был и на RED.
    await page.screenshot({ path: SHOT("after-ve-name60-360-light") })
    expect(overflowing(document.body)).toEqual([])
  })

  it('пустой список: плейсхолдер без переполнения', async () => {
    const w = mountAt(VariablesEditor, { modelValue: [] }, 360)
    await flushPromises()
    // Реальный i18n (ru) — проверяем смысл, а не ключ: строк нет, пустое
    // состояние и кнопка добавления на месте.
    expect(document.querySelectorAll('[id^="pv-name-"]').length).toBe(0)
    expect(w.text().length).toBeGreaterThan(0)
    expect(overflowing(document.body)).toEqual([])
  })

  it('битый JSON: ошибка видна, поле не блокирует ввод', async () => {
    const w = mountAt(VariablesEditor, { modelValue: BROKEN_JSON }, 520)
    await flushPromises()
    expect(w.text()).toContain('not valid JSON')
    const area = document.querySelector('[id^="pv-value-"]') as HTMLTextAreaElement
    expect(area.disabled).toBe(false)
  })
})

describe('WO-VT-3 audit: PresetPicker (табы, причина блокировки)', () => {
  it('расстояние между уровнями табов ≥12px', async () => {
    mountAt(PresetPicker, { processKey: 'orderProcess', targetKind: 'START' }, 520)
    await flushPromises()
    const tablists = [...document.querySelectorAll('[role="tablist"]')] as HTMLElement[]
    expect(tablists.length).toBe(2)
    const gap = tablists[1].getBoundingClientRect().top - tablists[0].getBoundingClientRect().bottom
    expect(gap).toBeGreaterThanOrEqual(12)
  })

  it('черновая пустая строка НЕ показывает «Заполните обязательные поля:» с пустым списком', async () => {
    const w = mountAt(PresetPicker, { processKey: 'orderProcess', targetKind: 'START' }, 520)
    await flushPromises()
    // клик «+ Добавить переменную» создаёт черновик {name:'', value:''}
    const addBtn2 = w.findComponent(VariablesEditor).findAll('button')
      .find((b) => /Добавить переменную/.test(b.text()))
    expect(addBtn2).toBeTruthy()
    await addBtn2!.trigger('click')
    await flushPromises()
    const msg = [...document.querySelectorAll('p')].find((p) =>
      (p.textContent ?? '').includes('presetFillAskFields') || /Заполните обязательные поля/.test(p.textContent ?? ''),
    )
    expect(msg).toBeUndefined()
  })

  it('настоящее пустое «спросить»: имя в списке + клик фокусит поле', async () => {
    const w = mountAt(PresetPicker, { processKey: 'orderProcess', targetKind: 'START' }, 520)
    await flushPromises()
    // Именованная пустая строка через редактор = настоящее «спросить».
    const editor = w.findComponent(VariablesEditor)
    const addBtn2 = editor.findAll('button').find((b) => /Добавить переменную/.test(b.text()))
    expect(addBtn2).toBeTruthy()
    await addBtn2!.trigger('click')
    await flushPromises()
    const nameInputs = document.querySelectorAll('[id^="pv-name-"]') as NodeListOf<HTMLInputElement>
    expect(nameInputs.length).toBe(1)
    nameInputs[0].focus()
    nameInputs[0].value = 'needInput'
    nameInputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const msg = [...document.querySelectorAll('p')].find((p) =>
      /бязательные поля|equired fields|індетті/.test(p.textContent ?? ''),
    )!
    expect(msg, 'ask message lists the field').toBeTruthy()
    expect(msg.textContent).toContain('needInput')
    // Критерий 6: имя — кликабельная ссылка, фокусящая поле.
    const link = msg.querySelector('button, a') as HTMLElement | null
    expect(link, 'field name is a clickable link').toBeTruthy()
    link!.click()
    await flushPromises()
    expect(document.activeElement).toBe(nameInputs[0])
  })
})

describe('WO-VT-3 audit: PresetEditorDialog', () => {
  it.each([360, 960])('FIVE @%ipx: модалка без горизонтального переполнения', async (w) => {
    await page.viewport(Math.max(w, 360) + 40, 800)
    mountAt(
      PresetEditorDialog,
      { open: true, processKey: 'orderProcess', targetKind: 'START', targetRef: null, initialVariables: FIVE },
      w,
    )
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeTruthy()
    await page.screenshot({ path: SHOT(`after-editor-five-${w}`) })
    expect(overflowing(document.body)).toEqual([])
  })

  it('Esc закрывает диалог', async () => {
    const w = mountAt(
      PresetEditorDialog,
      { open: true, processKey: 'orderProcess', targetKind: 'START', targetRef: null, initialVariables: ONE },
      960,
    )
    await flushPromises()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(w.emitted('close')).toBeTruthy()
  })

  it('FORTY: подвал (Отмена/Сохранить) виден без прокрутки диалога', async () => {
    await page.viewport(1000, 800)
    mountAt(
      PresetEditorDialog,
      { open: true, processKey: 'orderProcess', targetKind: 'START', targetRef: null, initialVariables: FORTY },
      960,
    )
    await flushPromises()
    const dialog = document.querySelector('[role="dialog"]') as HTMLElement
    const saveBtn = [...dialog.querySelectorAll('button')].find((b) => /presetSave|Сохранить|save/.test(b.textContent ?? '')) as HTMLElement | undefined
    expect(saveBtn).toBeTruthy()
    const r = saveBtn!.getBoundingClientRect()
    // кнопка в пределах вьюпорта без ручной прокрутки
    expect(r.top).toBeGreaterThanOrEqual(0)
    expect(r.bottom).toBeLessThanOrEqual(800)
  })
})

describe('WO-VT-3 audit: ElementPresetsPanel', () => {
  it.each([280, 360, 560])('длинные названия @%ipx: без переполнения', async (w) => {
    ;(listPresets as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([longPreset(1), longPreset(2)])
    mountAt(ElementPresetsPanel, { processKey: 'orderProcess', targetKind: 'START', targetRef: null }, w)
    await flushPromises()
    await flushPromises()
    if (w === 280) await page.screenshot({ path: SHOT("after-panel-280") })
    expect(overflowing(document.body)).toEqual([])
  })

  it('видимость показана переводом, сырых PROCESS/PRIVATE нет', async () => {
    ;(listPresets as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([
      { ...longPreset(1), visibility: 'PROCESS' },
      { ...longPreset(2), visibility: 'PRIVATE' },
    ])
    mountAt(ElementPresetsPanel, { processKey: 'orderProcess', targetKind: 'START', targetRef: null }, 360)
    await flushPromises()
    await flushPromises()
    const body = document.body.textContent ?? ''
    expect(body).not.toContain('PROCESS')
    expect(body).not.toContain('PRIVATE')
  })
})

describe('WO-VT-3 audit: PresetManagerPanel + message/snapshot', () => {
  it('таблица @360: без горизонтального переполнения страницы', async () => {
    ;(listPresets as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([longPreset(1), longPreset(2), longPreset(3)])
    mountAt(PresetManagerPanel, { processKey: 'orderProcess' }, 360)
    await flushPromises()
    expect(overflowing(document.body)).toEqual([])
  })

  it('LONG с плейсхолдером {{seq}}: значение видно и не санитизируется инпутом', async () => {
    mountAt(VariablesEditor, { modelValue: [{ name: 'n', type: 'LONG', value: '{{seq}}' }] }, 520)
    await flushPromises()
    // Валидный по контракту VT-1 плейсхолдер обязан отображаться как текст:
    // <input type=number> молча стирает нечисловое при показе.
    const input = document.querySelector('[id^="pv-value-"]') as HTMLInputElement
    expect(input.value).toBe('{{seq}}')
  })

  it('InstanceMessagePanel: добор имени сообщения НЕ сносит введённые переменные', async () => {
    const w = mountAt(InstanceMessagePanel, { processKey: 'orderProcess' }, 520)
    await flushPromises()
    const nameInput = document.querySelector('#msg-name') as HTMLInputElement
    // Печатаем имя по буквам — пикер уже смонтирован (v-if по непустому).
    nameInput.focus()
    for (const ch of 'order') {
      nameInput.value += ch
      nameInput.dispatchEvent(new Event('input', { bubbles: true }))
      await flushPromises()
    }
    // Вводим переменную через редактор.
    const editor = w.findComponent(VariablesEditor)
    await editor.findAll('button').find((b) => /Добавить переменную/.test(b.text()))!.trigger('click')
    await flushPromises()
    await editor.find('input[id^="pv-name-"]').setValue('keepMe')
    await flushPromises()
    expect(editor.find('input[id^="pv-name-"]').element as HTMLInputElement).toBeTruthy()
    // Допечатываем имя — введённая переменная обязана выжить (key-remount!).
    nameInput.value += 'X'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const names = [...document.querySelectorAll('[id^="pv-name-"]')] as HTMLInputElement[]
    expect(names.map((n) => n.value)).toContain('keepMe')
  })

  it('PresetEditorDialog: двойной клик «Сохранить» шлёт один запрос', async () => {
    const svc = await import('@/services/presetService')
    const createMock = svc.createPreset as unknown as { mockClear: () => void; mock: { calls: unknown[] } }
    createMock.mockClear()
    const w = mountAt(
      PresetEditorDialog,
      { open: true, processKey: 'k', targetKind: 'START', targetRef: null, initialVariables: ONE },
      520,
    )
    await flushPromises()
    // WO-UI-27 доп.3: диалог — shadcn-портал, имя — в document.
    const dcName = document.querySelector('#preset-name') as HTMLInputElement
    dcName.value = 't1'
    dcName.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const save = [...document.querySelectorAll('button')].find((b) => /Сохранить/.test(b.textContent ?? ''))!
    // Настоящий даблклик: два события подряд БЕЗ await между ними (второй
    // обработчик стартует, пока первый ещё в полёте — guard saving ловит).
    const p1 = (async () => (save as HTMLButtonElement).click())()
    const p2 = (async () => (save as HTMLButtonElement).click())()
    await p1
    await p2
    await flushPromises()
    expect(createMock.mock.calls.length).toBe(1)
  })

  it('PresetManagerPanel: видимость переведена, а не сырой PRIVATE/PROCESS', async () => {
    ;(listPresets as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([
      { ...longPreset(1), visibility: 'PRIVATE' },
    ])
    mountAt(PresetManagerPanel, { processKey: 'orderProcess' }, 520)
    await flushPromises()
    await flushPromises()
    const body = document.body.textContent ?? ''
    // Сырые enum-значения сервера пользователю не показываем (i18n-ключи
    // presetVisibilityPrivate/Process уже есть в локалях).
    expect(body).not.toContain('PRIVATE')
    expect(body).not.toContain('PROCESS')
  })

  it('PresetManagerPanel: колонки «Где применяется»/«Элемент», why-строка есть', async () => {
    ;(listPresets as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([
      { ...longPreset(1), targetKind: 'USER_TASK', targetRef: 'taskA' },
      { ...longPreset(2), targetKind: 'START', targetRef: null },
    ])
    mountAt(PresetManagerPanel, { processKey: 'orderProcess' }, 520)
    await flushPromises()
    await flushPromises()
    // Реальный ru-i18n: сверяем ПЕРЕВЕДЁННЫЕ строки из словаря, не ключи.
    const body = document.body.textContent ?? ''
    expect(body).toContain(ru.presetWhereUsed)
    expect(body).toContain(ru.presetElementName)
    expect(body).not.toContain('Вид места')
    // START без ref — «Запуск процесса», а не «—».
    expect(body).toContain(ru.presetStartTargetRef)
    // Why-строка блока.
    expect(body).toContain(ru.presetWhyManager)
  })

  it('InstanceMessagePanel и SnapshotPanel объясняют себя (why-строки)', async () => {
    mountAt(InstanceMessagePanel, { processKey: 'orderProcess' }, 520)
    await flushPromises()
    mountAt(
      InstanceSnapshotPanel,
      { processKey: 'orderProcess', instanceVariables: [{ name: 'a', type: 'LONG', value: '1' }] },
      520,
    )
    await flushPromises()
    const body = document.body.textContent ?? ''
    expect(body).toContain(ru.presetWhyMessage)
    expect(body).toContain(ru.presetWhySnapshot)
  })

  it('InstanceSnapshotPanel диалог @320: без переполнения', async () => {
    mountAt(
      InstanceSnapshotPanel,
      { processKey: 'orderProcess', instanceVariables: [{ name: 'a', type: 'LONG', value: '1' }] },
      320,
    )
    await flushPromises()
    // Кнопка открытия — первая в панели (текст зависит от локали).
    const openBtn = hosts[0].querySelector('button') as HTMLElement
    expect(openBtn).toBeTruthy()
    openBtn.click()
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeTruthy()
    expect(overflowing(document.body)).toEqual([])
  })
})

describe('WO-VT-3 audit: тёмная тема + консоль + i18n', () => {
  it('FIVE @360 dark: без переполнения', async () => {
    mountAt(VariablesEditor, { modelValue: FIVE }, 360, { dark: true })
    await flushPromises()
    await page.screenshot({ path: SHOT("after-editor-five-360-dark") })
    expect(overflowing(document.body)).toEqual([])
  })

  it('рендер key-поверхностей в ru/kz: без сырых ключей и консольных ошибок', async () => {
    const errs: string[] = []
    const orig = console.error
    console.error = (...a: unknown[]) => { errs.push(String(a[0])) }
    try {
      for (const locale of ['ru', 'kz']) {
        mountAt(VariablesEditor, { modelValue: FIVE }, 520, { locale })
        await flushPromises()
        mountAt(PresetPicker, { processKey: 'orderProcess', targetKind: 'START' }, 520, { locale })
        await flushPromises()
      }
    } finally {
      console.error = orig
    }
    const body = document.body.textContent ?? ''
    expect(body).not.toMatch(/preset[A-Z]\w*/)
    expect(errs.filter((e) => !e.includes('scrollIntoView'))).toEqual([])
  })
})

describe('WO-VT-3 round 2 IA (CTO E-VT3-1): message bare, names, snapshot title', () => {
  it('InstanceMessagePanel bare @360: без карточки, публикация цела, без переполнения', async () => {
    const w = mountAt(InstanceMessagePanel, { processKey: 'orderProcess', bare: true }, 360)
    await flushPromises()
    // RED: пропа bare нет — карточная обёртка и заголовок на месте.
    expect(w.find('[data-testid="message-panel-bare"]').exists()).toBe(true)
    expect(w.find('[data-testid="message-panel-card"]').exists()).toBe(false)
    // Публикация доступна: имя + кнопка на месте.
    expect(w.find('#msg-name').exists()).toBe(true)
    expect(overflowing(document.body)).toEqual([])
  })

  it('PresetManagerPanel elementNames: ИМЯ из схемы, неизвестный ref — id как fallback', async () => {
    ;(listPresets as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([
      { ...longPreset(1), targetKind: 'USER_TASK', targetRef: 'taskA' },
      { ...longPreset(2), targetKind: 'USER_TASK', targetRef: 'unknownX' },
    ])
    mountAt(
      PresetManagerPanel,
      { processKey: 'orderProcess', elementNames: { taskA: 'Согласование заявки' } },
      520,
    )
    await flushPromises()
    await flushPromises()
    const body = document.body.textContent ?? ''
    // RED: elementNames не поддержан — виден сырой id taskA.
    expect(body).toContain('Согласование заявки')
    expect(body).toContain('unknownX')
    expect(overflowing(document.body)).toEqual([])
    await page.screenshot({ path: SHOT('after-manager-names-520') })
  })

  it('InstanceSnapshotPanel: диалог переименован «Сохранить переменные как шаблон»', async () => {
    // RED: ключа presetSnapshotDialogTitle нет в ru.json.
    expect(ru.presetSnapshotDialogTitle).toBeTruthy()
    const w = mountAt(
      InstanceSnapshotPanel,
      { processKey: 'orderProcess', instanceVariables: [{ name: 'a', type: 'LONG', value: '1' }] },
      520,
    )
    await flushPromises()
    const openBtn = hosts[0].querySelector('button') as HTMLElement
    openBtn.click()
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeTruthy()
    expect(document.body.textContent ?? '').toContain(ru.presetSnapshotDialogTitle)
    expect(overflowing(document.body)).toEqual([])
    w.unmount()
  })
})

describe('WO-VT-3 round 2 IA (CTO E-VT3-1): actions menu fits the header', () => {
  it('header actions @360: menu button inside the header row, no page overflow', async () => {
    await page.viewport(360, 800)
    mountAt(PageInstanceDetail, {}, 360)
    await flushPromises()
    await flushPromises()
    const menu = document.querySelector('[data-testid="instance-actions-menu"]') as HTMLElement | null
    expect(menu, 'actions menu renders in the header').toBeTruthy()
    // Меню — в шапке (рядом с кнопками Обновить/Диагностика), не отдельным рядом.
    const header = menu!.closest('div.flex.items-center.gap-2') as HTMLElement
    expect(header).toBeTruthy()
    expect(menu!.compareDocumentPosition(header) & Node.DOCUMENT_POSITION_CONTAINS).toBeTruthy()
    // Кнопка-меню — внутри шапки (containment выше); геометрия страницы —
    // по общей мерке сьюта overflowing() + скриншот (сырые rect здесь не
    // нужны: шапка шире хоста по построению стенда, merка — отсутствие
    // вылезающих элементов, а не ширина контейнера).
    expect(overflowing(document.body)).toEqual([])
    await page.screenshot({ path: SHOT('after-instance-actions-360') })
  })
})

describe('WO-VT-3 HOLD r1: JSON-фулскрин не теряет ввод (RT-1/RT-2)', () => {
  const JSON_ROWS: PresetVariable[] = [{ name: 'stages', type: 'JSON', value: '{"a":1}' }]

  async function openDialogWithFs() {
    const svc = await import('@/services/presetService')
    const createMock = svc.createPreset as unknown as { mockClear: () => void; mock: { calls: unknown[][] } }
    createMock.mockClear()
    const w = mountAt(
      PresetEditorDialog,
      { open: true, processKey: 'k', targetKind: 'START', targetRef: null, initialVariables: JSON_ROWS },
      960,
    )
    await flushPromises()
    // WO-UI-27 доп.3: диалог — shadcn-портал, имя — в document.
    const rtName = document.querySelector('#preset-name') as HTMLInputElement
    rtName.value = 't1'
    rtName.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    // Открываем фулскрин JSON через тулбар поля.
    const fsBtn = [...document.querySelectorAll('button')].find((b) =>
      (b.textContent ?? '').includes(ru.presetJsonFullscreen),
    ) as HTMLButtonElement
    expect(fsBtn, 'fullscreen button renders').toBeTruthy()
    fsBtn.click()
    await flushPromises()
    const fsArea = document.querySelector('[id$="-jsonfs"]') as HTMLTextAreaElement
    expect(fsArea, 'fullscreen textarea opens').toBeTruthy()
    return { w, createMock, fsArea }
  }

  it('RT-1: Esc в фулскрине возвращает в редактор, диалог НЕ закрывается, текст цел', async () => {
    const { w, fsArea } = await openDialogWithFs()
    // Ввод — как реальный набор (execCommand/insertText; голый value= +
    // синтетический input v-model в этом Chromium не кормит — проверено пробой).
    fsArea.focus()
    document.execCommand('selectAll', false)
    document.execCommand('insertText', false, '{"a":1,"b":2}')
    await flushPromises()
    fsArea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    // RED на 0fa5ef7d: ловушка usePresetModal закрывает ДИАЛОГ (emitted close
    // truthy), фулскрин остаётся открыт — ввод потерян вместе с диалогом.
    expect(w.emitted('close'), 'dialog must NOT close on Esc in fullscreen').toBeUndefined()
    expect(document.querySelector('[role="dialog"]'), 'dialog stays open').toBeTruthy()
    expect(document.querySelector('[id$="-jsonfs"]'), 'fullscreen closed by Esc-return').toBeFalsy()
    // Набранное сохранено в черновике фулскрина — повторное открытие видит его.
    const fsBtn2 = [...document.querySelectorAll('button')].find((b) =>
      (b.textContent ?? '').includes(ru.presetJsonFullscreen),
    ) as HTMLButtonElement
    fsBtn2.click()
    await flushPromises()
    const fsArea2 = document.querySelector('[id$="-jsonfs"]') as HTMLTextAreaElement
    expect(fsArea2.value).toContain('"b":2')
    w.unmount()
  })

  it('RT-2: Ctrl+Enter в фулскрине сохраняет НАБРАННОЕ, а не старое', async () => {
    const { w, createMock, fsArea } = await openDialogWithFs()
    fsArea.focus()
    document.execCommand('selectAll', false)
    document.execCommand('insertText', false, '{"a":1,"b":2}')
    await flushPromises()
    fsArea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }))
    await flushPromises()
    await flushPromises()
    // RED на 0fa5ef7d: save() вызван со старым variables — createPreset 1 раз
    // БЕЗ набранного; правки молча выброшены.
    expect(createMock.mock.calls.length).toBe(1)
    const payload = createMock.mock.calls[0][0] as { variables: Array<{ value: string }> }
    // value — строка JSON; парсим и проверяем поле (в сериализованном виде
    // кавычки экранированы, toContain('"b":2') там ложно падает).
    expect(JSON.parse(payload.variables[0].value)).toEqual({ a: 1, b: 2 })
    w.unmount()
  })
})

describe('WO-VT-3 HOLD r1 оформительские: меню ⋯ и guard снимка (RT-3/RT-4/RT-5)', () => {
  it('RT-3: выбор пункта меню ⋯ возвращает фокус на кнопку меню', async () => {
    // WO-UI-27: в карточке переменной меню нет (крестик ×); VariableRowMenu
    // живёт в панели элементных шаблонов — монтируем напрямую с безобидными
    // пунктами (выбор edit в панели открывал бы диалог и уводил фокус).
    const { default: RowMenu } = await import('@/widgets/presets/VariableRowMenu.vue')
    const w = mountAt(
      RowMenu,
      {
        label: 'menu',
        items: [
          { id: 'a', label: 'Alpha' },
          { id: 'b', label: 'Beta' },
        ],
        onSelect: () => {},
      },
      520,
    )
    await flushPromises()
    const menuBtn = document.querySelector('[data-testid="row-menu-button"]') as HTMLButtonElement
    expect(menuBtn, 'menu button renders').toBeTruthy()
    menuBtn.click()
    await flushPromises()
    const item = document.querySelector('[data-testid="row-menu-item-a"]') as HTMLButtonElement
    expect(item, 'item renders').toBeTruthy()
    item.click()
    await flushPromises()
    // Фокус возвращается после закрытия dismissable-слоя (~30 мс).
    await new Promise((r) => setTimeout(r, 150))
    // RED (VT-3): choose() не возвращал фокус (activeElement === body).
    expect(document.activeElement).toBe(menuBtn)
    w.unmount()
  })

  it('RT-4: ArrowDown/ArrowUp двигают фокус по пунктам меню', async () => {
    ;(listPresets as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([longPreset(1)])
    mountAt(ElementPresetsPanel, { processKey: 'orderProcess', targetKind: 'START', targetRef: null }, 520)
    await flushPromises()
    await flushPromises()
    const menuBtn = document.querySelector('[data-testid="row-menu-button"]') as HTMLButtonElement
    menuBtn.click()
    await flushPromises()
    const items = [...document.querySelectorAll('[role="menuitem"]')] as HTMLButtonElement[]
    expect(items.length).toBeGreaterThanOrEqual(2)
    items[0].focus()
    items[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flushPromises()
    // RED: стрелки не обрабатывались — фокус стоял на месте.
    expect(document.activeElement).toBe(items[1])
    ;(document.activeElement as HTMLButtonElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }),
    )
    await flushPromises()
    expect(document.activeElement).toBe(items[0])
  })

  it('RT-5: программное открытие снимка при 0 переменных не открывает диалог', async () => {
    const w = mountAt(
      InstanceSnapshotPanel,
      { processKey: 'k', processDefinitionId: 'def1', instanceVariables: [] },
      520,
    )
    await flushPromises()
    const panel = w.findComponent(InstanceSnapshotPanel)
    ;(panel.vm as unknown as { openDialog: () => void }).openDialog()
    await flushPromises()
    // RED: диалог открывался в обход :disabled (пустой список, сохранять нечего).
    expect(document.querySelector('[role="dialog"]')).toBeFalsy()
    w.unmount()
  })
})
