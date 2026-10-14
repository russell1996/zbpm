// @vitest-environment jsdom
/**
 * WO-UI-35 (критерии (а)–(д)): TruncatedText — обрезанный текст в ячейке
 * открывается в shadcn Dialog целиком, с копированием.
 *
 * Контракт (WO, владелец 2026-10-14):
 *  (а) короткий текст (NE обрезан: scrollWidth <= clientWidth) — без
 *      role="button", без tabindex, клик НЕ открывает диалог и НЕ гасит
 *      всплытие (поведение строки-контейнера сохраняется);
 *  (б) длинный текст (обрезан по scrollWidth/clientWidth) — role="button",
 *      tabindex=0, aria-label с действием; клик/Enter/Space открывают
 *      shadcn Dialog с ПОЛНЫМ текстом (хвост, не помещающийся в ячейку,
 *      читается в диалоге);
 *  (в) «Копировать» (shadcn Button) кладёт ПОЛНЫЙ текст в
 *      navigator.clipboard + тост «Скопировано»;
 *  (г) ОДИН элемент закрытия (крестик shadcn; кнопки «Закрыть» нет),
 *      Esc закрывает, фокус возвращается на ячейку;
 *  плюс: ResizeObserver-пересчёт при ресайзе (ячейка расширилась —
 *      кликабельность снята), клик по обрезанному тексту не утекает
 *      на строку (в IncidentList строка навигирует).
 *
 * Мутации (каждая обязана краснить):
 *  M1 — убрать guard «только если обрезан» (открывать всегда) → (а) красный;
 *  M2 — убрать Dialog целиком (title-только) → (б)/(в) красные.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import TruncatedText from './TruncatedText.vue'

// ---------- i18n / toast / clipboard / ResizeObserver ----------

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

const toastSuccess = vi.fn()
const toastError = vi.fn()
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: toastSuccess, error: toastError }),
}))

class ROStub {
  static instances: ROStub[] = []
  cb: ResizeObserverCallback
  observed: Element[] = []
  constructor(cb: ResizeObserverCallback) {
    this.cb = cb
    ROStub.instances.push(this)
  }
  observe(el: Element) {
    this.observed.push(el)
  }
  unobserve() { /* jsdom: no-op */ }
  disconnect() { /* jsdom: no-op */ }
  /** Симулировать ресайз наблюдаемого элемента. */
  fire() {
    this.cb(this as unknown as ResizeObserverEntry[], this as unknown as ResizeObserver)
  }
}

const LONG =
  'errors: variable with name assigneeEmployeeId is not present in the scope of the element '
  + '— full tail marker: ' + 'x'.repeat(400) + ' tail-end'
const SHORT = 'short message'

function setSizes(el: HTMLElement, scrollWidth: number, clientWidth: number) {
  Object.defineProperty(el, 'scrollWidth', { configurable: true, value: scrollWidth })
  Object.defineProperty(el, 'clientWidth', { configurable: true, value: clientWidth })
}

function until(cond: () => boolean, timeout = 4000): Promise<void> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const tick = () => {
      if (cond()) return resolve()
      if (Date.now() - start > timeout) return reject(new Error('timeout waiting for condition'))
      setTimeout(tick, 16)
    }
    tick()
  })
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

/**
 * Монтирует компонент в хост ширины 200px; возвращает span-ячейку.
 * span ищем по testid: компонент мульти-корневой (span + Dialog), поэтому
 * wrapper.element — обёртка, а не сама ячейка.
 */
function mountCell(value: string): { wrapper: VueWrapper; span: HTMLElement; host: HTMLElement } {
  const host = document.createElement('div')
  host.style.width = '200px'
  document.body.appendChild(host)
  const wrapper = mount(TruncatedText, {
    attachTo: host,
    props: { value, title: 'Message' },
  })
  mounted.push(wrapper)
  hosts.push(host)
  const span = wrapper.find('[data-testid="truncated-text"]').element as HTMLElement
  return { wrapper, span, host }
}

async function makeTruncated(span: HTMLElement) {
  setSizes(span, 999, 200) // content wider than the box → clipped
  ROStub.instances.at(-1)!.fire()
  await flushPromises()
}

beforeEach(() => {
  ROStub.instances = []
  vi.clearAllMocks()
  vi.stubGlobal('ResizeObserver', ROStub)
  vi.stubGlobal('navigator', {
    clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('WO-UI-35 TruncatedText (unit)', () => {
  it('(а) short text: no role=button, no tabindex, click opens NO dialog and bubbles', async () => {
    const { span, host } = mountCell(SHORT)
    // Текст поместился: scrollWidth <= clientWidth.
    setSizes(span, 80, 200)
    ROStub.instances.at(-1)!.fire()
    await flushPromises()

    expect(span.getAttribute('role'), 'short text must NOT be a button').toBeNull()
    expect(span.getAttribute('tabindex')).toBeNull()

    const nav = vi.fn()
    host.addEventListener('click', nav)
    span.click()
    await flushPromises()
    expect(document.querySelector('[role="dialog"]'), 'no dialog for short text').toBeNull()
    // Поведение контейнера (напр. навигация строки) не подавлено.
    expect(nav).toHaveBeenCalled()
  })

  it('(б) truncated text: role=button + tabindex + aria; click opens dialog with the FULL text', async () => {
    const { span } = mountCell(LONG)
    await makeTruncated(span)

    expect(span.getAttribute('role')).toBe('button')
    expect(span.getAttribute('tabindex')).toBe('0')
    expect(span.getAttribute('aria-label')).toBe('truncatedShowFull')

    span.click()
    await flushPromises()
    const dialog = document.querySelector('[role="dialog"]')
    expect(dialog, 'dialog must open on click').not.toBeNull()
    // Полный текст, включая хвост, который в ячейке не читается.
    expect(dialog!.textContent).toContain(LONG)
    expect(dialog!.textContent).toContain('tail-end')
    // Заголовок = название колонки/контекста (проп title).
    const title = dialog!.querySelector('[data-slot="dialog-title"], h2, h1, h3')
    expect(title?.textContent).toContain('Message')
  })

  it('(б) truncated text: Enter and Space open the dialog (keyboard)', async () => {
    const { span } = mountCell(LONG)
    await makeTruncated(span)

    span.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
    await flushPromises()
    expect(document.querySelector('[role="dialog"]'), 'Enter opens the dialog').not.toBeNull()
    // Закрываем (Esc) для чистоты.
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await until(() => document.querySelector('[role="dialog"]') === null)

    span.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }))
    await flushPromises()
    expect(document.querySelector('[role="dialog"]'), 'Space opens the dialog').not.toBeNull()
  })

  it('(в) Copy button puts the FULL text into the clipboard and toasts', async () => {
    const { span } = mountCell(LONG)
    await makeTruncated(span)
    span.click()
    await flushPromises()
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog).not.toBeNull()

    const copyBtn = dialog.querySelector('[data-testid="truncated-text-copy"]') as HTMLButtonElement
    expect(copyBtn, 'shadcn Copy button in the dialog').toBeTruthy()
    copyBtn.click()
    await flushPromises()

    expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1)
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(LONG)
    expect(toastSuccess).toHaveBeenCalledWith('copied')
    expect(toastError).not.toHaveBeenCalled()
  })

  it('(в) clipboard failure → error toast, no success', async () => {
    vi.mocked(navigator.clipboard.writeText).mockRejectedValueOnce(new Error('denied'))
    const { span } = mountCell(LONG)
    await makeTruncated(span)
    span.click()
    await flushPromises()
    const copyBtn = document.querySelector('[role="dialog"]')
      ?.querySelector('[data-testid="truncated-text-copy"]') as HTMLButtonElement
    copyBtn.click()
    await flushPromises()
    expect(toastError).toHaveBeenCalledTimes(1)
    expect(toastSuccess).not.toHaveBeenCalled()
  })

  it('(г) exactly ONE close element (shadcn X); no «Close» button; Esc closes; focus returns to the cell', async () => {
    const { span } = mountCell(LONG)
    await makeTruncated(span)
    span.focus()
    span.click()
    await flushPromises()
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog).not.toBeNull()

    const buttons = [...dialog.querySelectorAll('button')]
    // Крестик shadcn: sr-only подпись t('close') = 'close' (i18n-мок возвращает ключ).
    const closeButtons = buttons.filter((b) => (b.textContent ?? '').trim().toLowerCase() === 'close')
    expect(closeButtons, 'exactly one shadcn X close').toHaveLength(1)
    // Кнопки «Закрыть» в футере нет — единственный не-крестик — «Копировать».
    const copyBtn = buttons.find((b) => b.getAttribute('data-testid') === 'truncated-text-copy')
    expect(copyBtn, 'Copy button present').toBeTruthy()
    expect(buttons).toHaveLength(2)

    // Esc закрывает…
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await until(() => document.querySelector('[role="dialog"]') === null)
    // …и фокус возвращается на ячейку.
    await until(() => document.activeElement === span)
  })

  it('ResizeObserver: cell widened → text fits → clickability removed', async () => {
    const { span } = mountCell(LONG)
    await makeTruncated(span)
    expect(span.getAttribute('role')).toBe('button')

    // Ячейка расширилась: весь текст помещается.
    setSizes(span, 200, 999)
    ROStub.instances.at(-1)!.fire()
    await flushPromises()
    expect(span.getAttribute('role'), 'fitting text is not clickable').toBeNull()

    span.click()
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })

  it('value change re-measures: new long value becomes clickable after resize event', async () => {
    const { wrapper, span } = mountCell(SHORT)
    setSizes(span, 80, 200)
    ROStub.instances.at(-1)!.fire()
    await flushPromises()
    expect(span.getAttribute('role')).toBeNull()

    wrapper.setProps({ value: LONG })
    setSizes(span, 999, 200)
    ROStub.instances.at(-1)!.fire()
    await flushPromises()
    expect(span.getAttribute('role')).toBe('button')
  })

  it('click on TRUNCATED text does not leak to the row (IncidentList row navigates)', async () => {
    const { span, host } = mountCell(LONG)
    await makeTruncated(span)
    const nav = vi.fn()
    host.addEventListener('click', nav)
    span.click()
    await flushPromises()
    expect(nav, 'row click must NOT fire when the dialog opens').not.toHaveBeenCalled()
    expect(document.querySelector('[role="dialog"]')).not.toBeNull()
  })

  it('inherited attrs (class/style) from the call site reach the span, not dropped (fragment root)', () => {
    // P-11: класс передаётся в 6+ местах применения (напр. имя инстанса
    // `class="text-base font-semibold"`). Мульти-корневой Vue без явной
    // привязки attrs молча выбрасывает их (Vue warn «Extraneous non-props
    // attributes ... fragment root nodes») — визуальный регресс.
    const wrapper = mount(TruncatedText, {
      props: { value: SHORT, title: 'Message' },
      attrs: { class: 'text-base font-semibold', style: 'color: red' },
    })
    mounted.push(wrapper)
    const span = wrapper.find('[data-testid="truncated-text"]').element as HTMLElement
    expect(span.className).toContain('text-base')
    expect(span.className).toContain('font-semibold')
    expect(span.style.color).toBe('red')
  })
})
