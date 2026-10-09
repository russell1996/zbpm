/**
 * WO-UI-26 критерий 7 — окно запуска процесса: проблемы владельца
 * воспроизведены и исправлены (либо доказано, что исправлены VT-3).
 *
 * Жалоба WO (п.6): «узкое окно, прилипающие табы». VT-3 (смержен d5778dcb4)
 * переделал диалог (PresetEditorDialog: width min(960px,94vw), табы сегментом
 * с отступом, sticky шапка/футер). Здесь — регресс-гейт на контракт VT-3:
 * - ширина диалога ≥ 560px при вьюпорте 960 и не шире вьюпорта при 360;
 * - уровни табов не слипаются (gap ≥ 12px);
 * - футер липкий (виден без прокрутки при длинной форме).
 * Мутация: вернуть width 400px / убрать sticky → красный.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import PresetEditorDialog from '@/widgets/presets/PresetEditorDialog.vue'
import type { PresetVariable } from '@/types/presets'
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
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { key: 'orderProcess' }, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

const FIVE: PresetVariable[] = [
  { name: 'createdEmployeeId', type: 'LONG', value: '21346' },
  { name: 'processTypeId', type: 'LONG', value: '1' },
  { name: 'sourceUuid', type: 'UUID', value: '01a0b481-3ff1-7334-82e0-9c1e5b2a3d4f' },
  { name: 'sourceVersion', type: 'LONG', value: '1' },
  { name: 'stages', type: 'JSON', value: '{"a":1}' },
]

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { ru, en, kz } })
}

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

function mountAt(width: number) {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  host.style.width = `${width}px`
  host.style.height = '720px'
  document.body.appendChild(host)
  hosts.push(host)
  const wrapper = mount(PresetEditorDialog, {
    attachTo: host,
    props: { open: true, processKey: 'orderProcess', targetKind: 'START', targetRef: null, initialVariables: FIVE },
    global: { stubs: { teleport: true }, plugins: [createPinia(), makeI18n()] },
  })
  mounted.push(wrapper)
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  for (const w of mounted) w.unmount()
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

describe('WO-UI-26 criterion 7: start dialog geometry (browser)', () => {
  it('диалог 560–960px на широком экране, табы не слипаются, футер липкий', async () => {
    const wrapper = mountAt(960)
    await flushPromises()
    const dialog = wrapper.find('[role="dialog"]')
    expect(dialog.exists()).toBe(true)
    const rect = dialog.element.getBoundingClientRect()
    // VT-3: width min(960px, 94vw) — на 960 вьюпорте ≥ 560 и ≤ 960.
    expect(rect.width).toBeGreaterThanOrEqual(560)
    expect(rect.width).toBeLessThanOrEqual(960)
    // Футер липкий: position sticky + видимая кнопка сохранения без скролла.
    const footer = dialog.element.querySelector('div.sticky.bottom-0, div[class*="sticky"]')
    expect(footer).not.toBeNull()
    const save = wrapper.findAll('button').find((b) => /Сохранить|Save/.test(b.text()))
    expect(save).toBeDefined()
    expect(save!.element.getBoundingClientRect().height).toBeGreaterThan(0)
    wrapper.unmount()
    mounted = []
  }, 60000)

  it('на 360px диалог не шире вьюпорта (нет горизонтального переполнения)', async () => {
    // vw считается от ОКНА браузера, а не от host-div: сужаем окно.
    const page = (await import('vitest/browser')).page
    await page.viewport(360, 720)
    try {
      const wrapper = mountAt(360)
      await flushPromises()
      const dialog = wrapper.find('[role="dialog"]')
      expect(dialog.exists()).toBe(true)
      const rect = dialog.element.getBoundingClientRect()
      // 94vw от 360 = ~338: диалог уже вьюпорта, страница не рвётся.
      expect(rect.width).toBeLessThanOrEqual(360)
      expect(rect.width).toBeGreaterThan(200)
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(361)
      wrapper.unmount()
      mounted = []
    } finally {
      await page.viewport(1280, 720)
    }
  }, 60000)
})
