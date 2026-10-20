/**
 * WO-UI-38 — ГЕОМЕТРИЯ закрытого значения выбора шаблона (живой Chromium).
 *
 * Дефект, который чинит WO-UI-38: в закрытом значении ш��блона имя и его
 * видимость были СКЛЕЕНЫ («Start ApprovalУчастники процесса» — так владелец
 * и увидел). Проверка «склеены/не склеены» в jsdom невозможна: там нет
 * раскладки, поэтому здесь — настоящий Chromium и замеры прямоугольников:
 *
 *  - имя и видимость — РАЗНЫЕ элементы, между ними видимый разделитель « · »;
 *  - прямоугольники фрагментов НЕ пересекаются (нет наложения);
 *  - длинное имя обрезается многоточием, а видимость ОСТАЁТСЯ видна
 *    (не выдавлена за пределы триггера);
 *  - полный текст подписи доступен по наведению (title) — обрезка не
 *    отнимает текст;
 *  - то же самое в выпадающем списке и в режиме Combobox (>8 шаблонов).
 *
 * Замоканы только сетевые сервисы; i18n настоящий (ru).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { page } from 'vitest/browser'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import PresetPicker from '@/widgets/presets/PresetPicker.vue'
import type { VariablePreset } from '@/types/presets'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'
import '@/style.css'

const listPresetsMock = vi.hoisted(() => vi.fn())
const getPresetMock = vi.hoisted(() => vi.fn())

vi.mock('@/services/presetService', () => ({
  listPresets: listPresetsMock,
  getPreset: getPresetMock,
  createPreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn(),
  importPreset: vi.fn(),
  exportPreset: vi.fn(),
  getPresetHistory: vi.fn(),
  setPresetFavorite: vi.fn(),
  changePresetVisibility: vi.fn(),
  isPresetsDisabled: () => false,
  isPresetConflict: () => false,
  presetErrorCode: () => null,
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), loading: vi.fn() }),
}))

function makeI18n(locale: string) {
  return createI18n({ legacy: false, locale, fallbackLocale: 'en', messages: { ru, en, kz } })
}

function preset(i: number, name = `Шаблон ${i}`): VariablePreset {
  return {
    id: `p${i}`,
    processDefinitionKey: 'k',
    targetKind: 'START',
    targetRef: null,
    name,
    description: null,
    variables: [{ name: 'n', type: 'LONG', value: '7' }],
    ownerUserId: 'u1',
    visibility: 'PROCESS',
    favorite: false,
    createdAt: '2026-01-01',
    updatedAt: '2026-01-01',
    version: 1,
  } as unknown as VariablePreset
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  listPresetsMock.mockResolvedValue([preset(1), preset(2)])
  getPresetMock.mockImplementation(async (id: string) => preset(Number(id.replace('p', ''))))
})

afterEach(async () => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
  await page.viewport(1280, 720)
})

function mountAt(props: Record<string, unknown>, width: number) {
  const host = document.createElement('div')
  host.style.width = `${width}px`
  host.style.minHeight = '200px'
  host.style.background = '#fff'
  document.body.appendChild(host)
  hosts.push(host)
  const wrapper = mount(PresetPicker, {
    attachTo: host,
    props: props as never,
    global: { plugins: [createPinia(), makeI18n('ru')] },
  }) as unknown as VueWrapper
  mounted.push(wrapper)
  return wrapper
}

function q<T extends Element = HTMLElement>(sel: string): T {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return el as T
}

/** Открыть выпадающий список reka-Select (в живом браузере — pointerdown). */
async function openSelect() {
  const trigger = q<HTMLElement>('[data-testid="preset-select"]')
  trigger.dispatchEvent(
    new PointerEvent('pointerdown', { bubbles: true, button: 0, ctrlKey: false, pointerType: 'mouse' }),
  )
  await flushPromises()
  await new Promise((r) => setTimeout(r, 120))
}

/** Выбрать пункт выпадающего списка (reka-SelectItem выбирает по pointerup). */
async function selectOption(testid: string) {
  const opt = q<HTMLElement>(`[data-testid="${testid}"]`)
  opt.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, pointerType: 'mouse' }))
  await flushPromises()
  await new Promise((r) => setTimeout(r, 120))
}

/**
 * Фрагменты подписи: имя, разделитель, видимость.
 * Разделитель может ОТСУТСТВОВАТЬ (мутант «убрать разделитель») — тогда
 * ассерт падает с понятным сообщением, а не с TypeError.
 */
function labelParts(root: Element, testid = 'preset-selected-label') {
  const label = root.querySelector(`[data-testid="${testid}"]`)
  if (!label) throw new Error(`${testid} not found`)
  return {
    label: label as HTMLElement,
    name: label.querySelector('[data-testid="preset-label-name"]') as HTMLElement | null,
    separator: label.querySelector('[data-testid="preset-label-separator"]') as HTMLElement | null,
    visibility: label.querySelector('[data-testid="preset-label-visibility"]') as HTMLElement | null,
  }
}

describe('WO-UI-38: закрытое значение — имя и видимость разделены, не склеены', () => {
  it('Select: два фрагмента, видимый разделитель « · », прямоугольники не пересекаются', async () => {
    mountAt({ processKey: 'k', targetKind: 'START' }, 520)
    await flushPromises()
    await openSelect()
    await selectOption('preset-opt-p1')

    const trigger = q<HTMLElement>('[data-testid="preset-select"]')
    const { name, separator, visibility } = labelParts(trigger)

    expect(name, 'имя — отдельный элемент').toBeTruthy()
    expect(name!.textContent, 'имя шаблона показано').toContain('Шаблон 1')
    expect(visibility, 'видимость — отдельный элемент').toBeTruthy()
    expect(visibility!.textContent, 'видимость показана').toContain('Участники процесса')
    expect(separator, 'разделитель « · » — отдельный элемент').toBeTruthy()
    expect(separator!.textContent, 'разделитель — « · »').toContain('·')

    // Разделитель ВИДЕН (не схлопнут в ноль) и стоит между фрагментами.
    const sep = separator!.getBoundingClientRect()
    const n = name!.getBoundingClientRect()
    const v = visibility!.getBoundingClientRect()
    expect(sep.width, 'разделитель имеет ширину').toBeGreaterThan(0)
    expect(n.right, 'имя заканчивается до разделителя').toBeLessThanOrEqual(sep.left + 1)
    expect(sep.right, 'разделитель заканчивается до видимости').toBeLessThanOrEqual(v.left + 1)

    // Фрагменты НЕ наложены друг на друга (дефект «склейки» = наложение/слипание).
    expect(n.right, 'прямоугольники фрагментов не пересекаются').toBeLessThanOrEqual(v.left + 1)

    // Видимость — мелким приглушённым шрифтом (как в ElementPresetsPanel).
    const fs = parseFloat(getComputedStyle(visibility!).fontSize)
    const fsTrigger = parseFloat(getComputedStyle(trigger).fontSize)
    expect(fs, 'видимость мельче основного текста').toBeLessThan(fsTrigger)
  })

  it('Select: полный текст подписи доступен по наведению (title) — обрезка не отнимает текст', async () => {
    mountAt({ processKey: 'k', targetKind: 'START' }, 520)
    await flushPromises()
    await openSelect()
    await selectOption('preset-opt-p1')

    const trigger = q<HTMLElement>('[data-testid="preset-select"]')
    const title = trigger.getAttribute('title') ?? ''
    expect(title, 'title содержит имя').toContain('Шаблон 1')
    expect(title, 'title содержит видимость').toContain('Участники процесса')
  })

  it('Select: длинное имя обрезается, видимость остаётся видимой (не выдавлена)', async () => {
    const long = 'Start Approval ' + 'очень длинное имя шаблона '.repeat(4)
    listPresetsMock.mockResolvedValue([preset(1, long)])
    mountAt({ processKey: 'k', targetKind: 'START' }, 360)
    await flushPromises()
    await openSelect()
    await selectOption('preset-opt-p1')

    const trigger = q<HTMLElement>('[data-testid="preset-select"]')
    const { name, visibility } = labelParts(trigger)

    expect(name, 'имя — отдельный элемент').toBeTruthy()
    expect(name!.scrollWidth, 'имя реально обрезано (многоточие)').toBeGreaterThan(name!.clientWidth + 1)
    expect(visibility, 'видимость — отдельный элемент').toBeTruthy()
    const t = trigger.getBoundingClientRect()
    const v = visibility!.getBoundingClientRect()
    expect(v.width, 'видимость не схлопнута').toBeGreaterThan(0)
    expect(v.right, 'видимость не вылезает за триггер').toBeLessThanOrEqual(t.right + 1)
    expect(v.left, 'видимость не вылезает за левый край триггера').toBeGreaterThanOrEqual(t.left - 1)
  })

  it('список: пункт выпадающего списка тоже разделён (имя / « · » / видимость)', async () => {
    mountAt({ processKey: 'k', targetKind: 'START' }, 520)
    await flushPromises()
    await openSelect()

    const opt = q<HTMLElement>('[data-testid="preset-opt-p2"]')
    const { name, separator, visibility } = labelParts(opt, 'preset-item-label')
    expect(name, 'имя — отдельный элемент').toBeTruthy()
    expect(name!.textContent).toContain('Шаблон 2')
    expect(visibility, 'видимость — отдельный элемент').toBeTruthy()
    expect(visibility!.textContent).toContain('Участники процесса')
    expect(separator, 'разделитель « · » — отдельный элемент').toBeTruthy()
    expect(separator!.textContent).toContain('·')
    const sep = separator!.getBoundingClientRect()
    const n = name!.getBoundingClientRect()
    const v = visibility!.getBoundingClientRect()
    expect(sep.width, 'разделитель виден').toBeGreaterThan(0)
    expect(n.right, 'фрагменты пункта не пересекаются').toBeLessThanOrEqual(v.left + 1)
  })

  it('Combobox (>8 шаблонов): закрытое значение разделено так же', async () => {
    listPresetsMock.mockResolvedValue(Array.from({ length: 10 }, (_, i) => preset(i + 1)))
    mountAt({ processKey: 'k', targetKind: 'START' }, 520)
    await flushPromises()

    const trigger = q<HTMLElement>('[data-testid="preset-select"]')
    trigger.click()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 120))
    const opt = q<HTMLElement>('[data-testid="preset-opt-p1"]')
    // reka ComboboxItem выбирает по click (ListboxItem onClick), не по pointerup.
    opt.dispatchEvent(new MouseEvent('click', { bubbles: true, button: 0 }))
    await flushPromises()
    await new Promise((r) => setTimeout(r, 120))

    const { name, separator, visibility } = labelParts(trigger)
    expect(name, 'имя — отдельный элемент').toBeTruthy()
    expect(name!.textContent).toContain('Шаблон 1')
    expect(visibility, 'видимость — отдельный элемент').toBeTruthy()
    expect(visibility!.textContent).toContain('Участники процесса')
    expect(separator, 'разделитель « · » — отдельный элемент').toBeTruthy()
    expect(separator!.textContent).toContain('·')
    const n = name!.getBoundingClientRect()
    const v = visibility!.getBoundingClientRect()
    expect(n.right, 'фрагменты в Combobox не пересекаются').toBeLessThanOrEqual(v.left + 1)
  })
})
