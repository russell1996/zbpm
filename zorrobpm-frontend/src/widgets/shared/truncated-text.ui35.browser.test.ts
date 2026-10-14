/**
 * WO-UI-35 (реальный Chromium): TruncatedText — живая геометрия.
 *
 * jsdom не считает layout (scrollWidth/clientWidth = 0), поэтому здесь —
 * то, что видит пользователь:
 *  - длинный текст в 220px-ячейке РЕАЛЬНО клиппится (computed overflow
 *    hidden + ellipsis + nowrap, scrollWidth > clientWidth по замеру) и
 *    получает role="button" (ResizeObserver-договор, не хардкод);
 *  - короткий текст — не кликабелен (нет role="button");
 *  - клик → shadcn Dialog с ПОЛНЫМ текстом: <pre> переносит по словам
 *    (whitespace-pre-wrap + break-words), внутренняя ширина без
 *    горизонтального переполнения (pre.scrollWidth <= pre.clientWidth),
 *    max-height + внутренний скролл;
 *  - 360px-контракт диалога: ширина `100%` (w-full, containing block =
 *    вьюпорт) + border-box + max-w-2xl — на 360px-вьюпорте диалог = 360px
 *    без горизонтального скролла (реальные замеры: потолок 672px работает
 *    на 1280px; та же механика в 360px-контейнере = ровно 360px);
 *  - ресайз: хост расширили → текст поместился → role="button" снят
 *    (ResizeObserver реально пересчитывает, «обновляется при ресайзе»);
 *  - фокус: после Esc фокус возвращается на span-ячейку.
 *
 * Мутации: убрать условие «только если обрезан» → «короткий не кликабелен»
 * красный; убрать ResizeObserver-пересчёт → «ресайз» красный.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import TruncatedText from './TruncatedText.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

import '@/style.css'

const LONG =
  'errors: variable with name assigneeEmployeeId is not present in the scope of the element — '
  + 'a long server-side explanation that never fits into a 220px cell, '
  + 'tail-marker-UI35-7f3a9c'
const SHORT = 'Короткое сообщение'

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { ru, en, kz } })
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

function mountHost(width: number): HTMLElement {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  host.style.width = `${width}px`
  host.style.height = '200px'
  document.body.appendChild(host)
  hosts.push(host)
  return host
}

function spanOf(wrapper: VueWrapper): HTMLElement {
  // Мульти-корневой компонент: wrapper.element — обёртка VTU, а не span.
  return wrapper.find('[data-testid="truncated-text"]').element as HTMLElement
}

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

beforeEach(() => {
  document.body.innerHTML = ''
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

describe('WO-UI-35 TruncatedText (browser, real Chromium)', () => {
  it('long text in a 220px cell: really clipped, role=button, click → dialog with the FULL text', async () => {
    const host = mountHost(220)
    const wrapper = mount(TruncatedText, {
      attachTo: host,
      props: { value: LONG, title: 'Message' },
      global: { plugins: [makeI18n()] },
    })
    mounted.push(wrapper)
    await flushPromises()
    const span = spanOf(wrapper)
    // ResizeObserver срабатывает после первой layout-отрисовки.
    await until(() => span.classList.contains('cursor-pointer'), 8000)
    const cs = getComputedStyle(span)
    // Живой клиппинг (не только класс): overflow hidden + ellipsis + nowrap.
    expect(cs.overflow).toBe('hidden')
    expect(cs.textOverflow).toBe('ellipsis')
    expect(cs.whiteSpace).toBe('nowrap')
    expect(span.scrollWidth).toBeGreaterThan(span.clientWidth)
    expect(span.getAttribute('role')).toBe('button')
    expect(span.getAttribute('tabindex')).toBe('0')
    expect(span.getAttribute('aria-label')).toBeTruthy()

    // Клик (нативный, как у пользователя) → диалог.
    span.click()
    await until(() => document.querySelector('[role="dialog"]') !== null, 8000)
    const dialog = document.querySelector('[role="dialog"]') as HTMLElement
    // Полный текст, включая хвост.
    expect(dialog.textContent).toContain('tail-marker-UI35-7f3a9c')
    expect(dialog.textContent).toContain(LONG.slice(0, 60))

    const pre = document.querySelector('[data-testid="truncated-text-content"]') as HTMLElement
    expect(pre).not.toBeNull()
    const preCs = getComputedStyle(pre)
    // Моноширинный перенос по словам (как JSON-диалог VariablesEditor).
    expect(preCs.fontFamily.toLowerCase()).toContain('mono')
    expect(preCs.whiteSpace).toContain('pre-wrap')
    expect(preCs.overflowWrap).toBe('break-word')
    // Никакого горизонтального переполнения внутри pre.
    expect(pre.scrollWidth).toBeLessThanOrEqual(pre.clientWidth + 1)
    // Max-height + внутренний скролл.
    expect(preCs.maxHeight).not.toBe('none')
    expect(['auto', 'scroll']).toContain(preCs.overflowY)
    // Заголовок диалога = название колонки.
    const title = dialog.querySelector('[data-testid="truncated-text-title"]')
    expect(title?.textContent).toBe('Message')
    wrapper.unmount()
    mounted = []
  }, 60000)

  it('short text: not clipped, NOT clickable (no role=button), click opens nothing', async () => {
    const host = mountHost(400)
    const wrapper = mount(TruncatedText, {
      attachTo: host,
      props: { value: SHORT, title: 'Message' },
      global: { plugins: [makeI18n()] },
    })
    mounted.push(wrapper)
    await flushPromises()
    const span = spanOf(wrapper)
    await new Promise((r) => setTimeout(r, 300)) // RO-кадр гарантированно прошёл
    expect(span.scrollWidth).toBeLessThanOrEqual(span.clientWidth)
    expect(span.getAttribute('role')).toBeNull()
    expect(span.getAttribute('tabindex')).toBeNull()
    span.click()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 300))
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  }, 30000)

  it('resize: host widened → text fits → role=button removed (ResizeObserver re-measures)', async () => {
    const host = mountHost(220)
    const wrapper = mount(TruncatedText, {
      attachTo: host,
      props: { value: LONG, title: 'Message' },
      global: { plugins: [makeI18n()] },
    })
    mounted.push(wrapper)
    const span = spanOf(wrapper)
    await until(() => span.classList.contains('cursor-pointer'), 8000)

    // Расширяем хост: весь текст помещается (TEXT ≈ 3400px широким при
    // текущем шрифте — хост берём шире вьюпорта, замер относительный).
    host.style.width = '4000px'
    await until(() => !span.classList.contains('cursor-pointer'), 8000)
    expect(span.getAttribute('role')).toBeNull()
    expect(span.scrollWidth).toBeLessThanOrEqual(span.clientWidth)
  }, 60000)

  it('360px dialog contract: width 100% + border-box + max-w — no fixed px width, no h-overflow at 360', async () => {
    const host = mountHost(360)
    const wrapper = mount(TruncatedText, {
      attachTo: host,
      props: { value: LONG, title: 'Message' },
      global: { plugins: [makeI18n()] },
    })
    mounted.push(wrapper)
    const span = spanOf(wrapper)
    await until(() => span.classList.contains('cursor-pointer'), 8000)
    span.click()
    await until(() => document.querySelector('[role="dialog"]') !== null, 8000)
    const dialog = document.querySelector('[role="dialog"]') as HTMLElement

    const cs = getComputedStyle(dialog)
    // 360px-контракт: ширина объявлена 100% (w-full — containing block у
    // position:fixed = вьюпорт), border-box (падинги ВНУТРИ), потолок
    // max-width 42rem. Computed width резолвится в px (min(100vw, 672px)),
    // поэтому механизм доказан двумя реальными замерами:
    //  (1) на текущем 1280px-вьюпорте диалог = 672px — clamped ПОТОЛКОМ,
    //      а не фиксированной шириной (иначе был бы 1280px);
    //  (2) та же самая CSS-механика (width:100% + тот же max-width +
    //      border-box) в 360px-контейнере реально меряется = 360px,
    //      без горизонтального переполнения.
    expect(cs.boxSizing).toBe('border-box')
    expect(dialog.classList.contains('w-full')).toBe(true) // width: 100%
    expect(parseFloat(cs.maxWidth)).toBeLessThanOrEqual(42 * 16)
    const rect = dialog.getBoundingClientRect()
    // (1) потолок работает: на 1280px вьюпорте диалог не 1280, а ≤ 672.
    expect(rect.width).toBeLessThanOrEqual(42 * 16 + 1)
    expect(rect.left).toBeGreaterThanOrEqual(0)
    // (2) механика на 360px: тот же width:100% + max-width + border-box.
    const box360 = document.createElement('div')
    box360.style.cssText = 'position: relative; width: 360px; height: 400px'
    document.body.appendChild(box360)
    const inner360 = document.createElement('div')
    inner360.style.cssText =
      `position: absolute; top: 0; left: 0; width: 100%; ` +
      `max-width: ${cs.maxWidth}; box-sizing: ${cs.boxSizing}`
    box360.appendChild(inner360)
    expect(inner360.getBoundingClientRect().width).toBe(360)
    expect(inner360.scrollWidth).toBeLessThanOrEqual(inner360.clientWidth + 1)
    box360.remove()
    // Pre внутри: pre-wrap + break-words — горизонтальный скролл невозможен
    // ни на какой ширине (слова переносятся, a не переполняют).
    const pre = document.querySelector('[data-testid="truncated-text-content"]') as HTMLElement
    expect(pre.scrollWidth).toBeLessThanOrEqual(pre.clientWidth + 1)
  }, 60000)

  it('Esc closes and focus returns to the cell (real focus, real Chromium)', async () => {
    const host = mountHost(220)
    const wrapper = mount(TruncatedText, {
      attachTo: host,
      props: { value: LONG, title: 'Message' },
      global: { plugins: [makeI18n()] },
    })
    mounted.push(wrapper)
    const span = spanOf(wrapper)
    await until(() => span.classList.contains('cursor-pointer'), 8000)
    span.focus()
    expect(document.activeElement).toBe(span)
    span.click()
    await until(() => document.querySelector('[role="dialog"]') !== null, 8000)
    expect(document.activeElement).not.toBe(span) // фокус ушёл в диалог (trap)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await until(() => document.querySelector('[role="dialog"]') === null, 8000)
    await until(() => document.activeElement === span, 4000)
  }, 60000)
})
