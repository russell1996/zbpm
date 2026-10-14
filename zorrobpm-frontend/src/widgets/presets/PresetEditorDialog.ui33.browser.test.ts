/**
 * WO-UI-33 — PresetEditorDialog: одно закрытие, единый «вес» кнопок подвала.
 *
 * Владелец: «че за хуйня закрыть и кнопка крестика» — в шапке налезали ДВА
 * закрытия: текстовая кнопка «Закрыть» + встроенный крестик shadcn
 * (DialogScrollContent, absolute top-3 right-3). Подвал был из разных «весов»:
 * Удалить/История — link size=sm, «Сделать личным» — outline size=sm text-xs,
 * Отмена/Сохранить — default.
 *
 * Регресс-гейт (реальный Chromium, реальные getBoundingClientRect — jsdom
 * не считает layout):
 * - в шапке ровно один элемент закрытия (роль button, имя close), и он не
 *   пересекается со звездой избранного на 360/1280/1920;
 * - все кнопки подвала одной высоты (±1px), каждая ≥ 44px (касание),
 *   «Сделать личным» не меньше «Отмена»;
 * - 360 px: диалог без горизонтального скролла, ни одна кнопка подвала не
 *   выходит за правый край;
 * - Esc и клик по крестику закрывают диалог; Esc в JSON-фулскрине — возврат
 *   в редактор без закрытия диалога (RT-1, UI-27).
 * Мутации: вернуть кнопку «Закрыть» → красный (2 элемента закрытия,
 * пересечение со звездой); вернуть size="sm" в подвале → красный (разные
 * высоты, < 44px).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, ref, type Ref } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import PresetEditorDialog from './PresetEditorDialog.vue'
import '@/style.css'

const mockGet = vi.hoisted(() => vi.fn())
vi.mock('@/services/presetService', () => ({
  createPreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn(),
  getPreset: mockGet,
  getPresetHistory: vi.fn(),
  setPresetFavorite: vi.fn(),
  changePresetVisibility: vi.fn(),
  isPresetConflict: (e: unknown) => (e as { conflict?: boolean })?.conflict === true,
  presetErrorCode: (e: unknown) => (e as { code?: string })?.code ?? null,
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}))

const PRESET = {
  id: 'p1',
  processDefinitionKey: 'k',
  targetKind: 'START',
  targetRef: null,
  name: 'base',
  description: '',
  variables: [{ name: 'n', type: 'LONG', value: '1' }],
  ownerUserId: 'u1',
  visibility: 'PRIVATE',
  favorite: false,
  createdAt: '2026-01-01',
  updatedAt: '2026-01-01',
  version: 3,
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []
let openRef: Ref<boolean> | null = null

function mountDialog(props: { presetId?: string | null } = {}) {
  openRef = ref(true)
  const open = openRef
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  host.style.width = '1280px'
  host.style.height = '720px'
  document.body.appendChild(host)
  hosts.push(host)
  // Хост с управляемым open: close → open=false, и портал физически уходит
  // (контролируемый reka-ui Dialog не закроется сам без смены пропа).
  const wrapper = mount(
    defineComponent({
      render: () =>
        h(PresetEditorDialog, {
          open: open.value,
          processKey: 'k',
          targetKind: 'START',
          targetRef: null,
          ...(props.presetId ? { presetId: props.presetId } : {}),
          // h(): слушатель события emit('close') — именно onClose (не «close»).
          onClose: () => {
            open.value = false
          },
        }),
    }),
    { attachTo: host },
  )
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

/** Кнопки подвала — по i18n-ключам (мок t(k)=k), edit-режим: 5 кнопок. */
function footerButtons(dlg: HTMLElement): HTMLButtonElement[] {
  const keys = ['presetDelete', 'presetHistory', 'presetShareWithProcess', 'cancel', 'save']
  return keys.map((k) => {
    const btn = [...dlg.querySelectorAll('button')].find(
      (b) => (b.textContent ?? '').trim() === k,
    ) as HTMLButtonElement | undefined
    expect(btn, `footer button "${k}" renders`).toBeTruthy()
    return btn!
  })
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  mockGet.mockResolvedValue({ ...PRESET })
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const hst of hosts) hst.remove()
  hosts = []
  document.body.innerHTML = ''
})

describe('WO-UI-33: PresetEditorDialog (browser)', () => {
  it.each([
    { viewport: 360 },
    { viewport: 1280 },
    { viewport: 1920 },
  ])('шапка: ровно одно закрытие, не пересекается со звездой @viewport px', async ({ viewport }) => {
    const page = (await import('vitest/browser')).page
    await page.viewport(viewport, 800)
    try {
      mountDialog({ presetId: 'p1' })
      await settle()
      const dlg = dialog()
      expect(dlg, 'dialog renders in portal').not.toBeNull()
      const closeBtns = [...dlg!.querySelectorAll('button')].filter(
        (b) => (b.textContent ?? '').trim().toLowerCase() === 'close',
      )
      expect(closeBtns, 'ровно один элемент закрытия (крестик shadcn)').toHaveLength(1)
      const star = dlg!.querySelector('button[aria-pressed]') as HTMLElement | null
      expect(star, 'звезда избранного в шапке (edit-режим)').toBeTruthy()
      const x = closeBtns[0].getBoundingClientRect()
      const s = star!.getBoundingClientRect()
      const intersects =
        x.left < s.right - 1 && s.left < x.right - 1 && x.top < s.bottom - 1 && s.top < x.bottom - 1
      expect(intersects, 'крестик не перекрывает звезду').toBe(false)
    } finally {
      await page.viewport(1280, 800)
    }
  }, 60000)

  it('подвал: все кнопки одной высоты (±1px), каждая ≥ 44px, «Сделать личным» ≥ «Отмена»', async () => {
    const page = (await import('vitest/browser')).page
    await page.viewport(1280, 800)
    try {
      mountDialog({ presetId: 'p1' })
      await settle()
      const dlg = dialog()
      expect(dlg, 'dialog renders in portal').not.toBeNull()
      const btns = footerButtons(dlg!)
      const heights = btns.map((b) => b.getBoundingClientRect().height)
      const max = Math.max(...heights)
      const min = Math.min(...heights)
      expect(max - min, `heights equal (got ${heights.map((h) => h.toFixed(1)).join('/')})`)
        .toBeLessThanOrEqual(1)
      for (const b of btns) {
        const h = b.getBoundingClientRect().height
        expect(h, `"${b.textContent}" height ≥ 44px`).toBeGreaterThanOrEqual(44)
      }
      const share = btns.find((b) => (b.textContent ?? '').includes('presetShareWithProcess'))!
      const cancel = btns.find((b) => (b.textContent ?? '').trim() === 'cancel')!
      expect(share.getBoundingClientRect().height)
        .toBeGreaterThanOrEqual(cancel.getBoundingClientRect().height)
    } finally {
      await page.viewport(1280, 800)
    }
  }, 60000)

  it('360 px: подвал переносится строками, горизонтального скролла нет', async () => {
    const page = (await import('vitest/browser')).page
    await page.viewport(360, 800)
    try {
      mountDialog({ presetId: 'p1' })
      await settle()
      const dlg = dialog()
      expect(dlg, 'dialog renders in portal @360').not.toBeNull()
      const r = dlg!.getBoundingClientRect()
      expect(r.width, 'диалог не шире вьюпорта').toBeLessThanOrEqual(360 + 1)
      expect(dlg!.scrollWidth, 'нет внутреннего горизонтального оверфлоу')
        .toBeLessThanOrEqual(dlg!.clientWidth + 1)
      for (const b of footerButtons(dlg!)) {
        const br = b.getBoundingClientRect()
        expect(br.right, `"${b.textContent}" не выходит за правый край вьюпорта`)
          .toBeLessThanOrEqual(360 + 1)
        expect(br.left, `"${b.textContent}" не выходит за левый край`).toBeGreaterThanOrEqual(-1)
      }
    } finally {
      await page.viewport(1280, 800)
    }
  }, 60000)

  it('Esc и клик по крестику закрывают диалог', async () => {
    const page = (await import('vitest/browser')).page
    await page.viewport(1280, 800)
    try {
      // Esc
      mountDialog({ presetId: 'p1' })
      await settle()
      expect(dialog(), 'dialog open before Esc').not.toBeNull()
      // vitest-browser `page` не даёт .keyboard — паттерн репо (vt3-audit):
      // keydown-событие, всплывающее до window-слушателя reka-ui (onKeyStroke).
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
      await settle()
      expect(dialog(), 'dialog closes on Esc').toBeNull()
      // Крестик
      openRef!.value = true
      await settle()
      const dlg = dialog()
      expect(dlg, 'dialog reopened').not.toBeNull()
      const closeBtn = [...dlg!.querySelectorAll('button')].find(
        (b) => (b.textContent ?? '').trim().toLowerCase() === 'close',
      ) as HTMLButtonElement | undefined
      expect(closeBtn, 'крестик на месте').toBeTruthy()
      closeBtn!.click()
      await settle()
      expect(dialog(), 'dialog closes on X click').toBeNull()
    } finally {
      await page.viewport(1280, 800)
    }
  }, 60000)

  it('Esc в JSON-фулскрине: возврат в редактор, диалог остаётся открыт', async () => {
    const page = (await import('vitest/browser')).page
    await page.viewport(1280, 800)
    try {
      const wrapper = mountDialog({ presetId: 'p1' })
      await settle()
      // JSON-строка → кнопка фулскрина.
      const ve = wrapper.findComponent({ name: 'VariablesEditor' })
      expect(ve.exists(), 'VariablesEditor в дереве').toBe(true)
      ve.vm.$emit('update:modelValue', [
        { name: 'j', type: 'JSON', value: '{"a":1}', allowEmptyString: null },
      ])
      await settle()
      const fsBtn = [...document.querySelectorAll('button')].find((b) =>
        (b.textContent ?? '').includes('presetJsonFullscreen'),
      ) as HTMLButtonElement | undefined
      expect(fsBtn, 'fullscreen button renders').toBeTruthy()
      fsBtn!.click()
      await settle()
      const fsArea = document.querySelector('[id$="-jsonfs"]') as HTMLElement | null
      expect(fsArea, 'fullscreen editor opens').not.toBeNull()
      fsArea!.focus()
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
      await settle()
      expect(document.querySelector('[id$="-jsonfs"]'), 'фулскрин закрыт (Esc-возврат)').toBeNull()
      expect(dialog(), 'главный диалог остаётся открыт').not.toBeNull()
    } finally {
      await page.viewport(1280, 800)
    }
  }, 60000)
})
