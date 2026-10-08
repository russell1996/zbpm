/**
 * WO-VT-1 (фронт): живые скриншоты ключевых экранов в реальном Chromium
 * (прецедент — WO-ACL-14 `shots/`). Реальный vue-i18n (en), замоканы только
 * сетевые сервисы. Скриншоты — артефакты отчёта, ассерты — страховка, что
 * снято именно рабочее состояние (видимые размеры ненулевые).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { page } from 'vitest/browser'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import VariablesEditor from '@/widgets/presets/VariablesEditor.vue'
import PresetEditorDialog from '@/widgets/presets/PresetEditorDialog.vue'
import StartForm from '@/pages/processes/StartForm.vue'
import type { PresetVariable } from '@/types/presets'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'
import '@/style.css'

vi.mock('@/services/presetService', () => ({
  listPresets: vi.fn().mockResolvedValue([]),
  getPreset: vi.fn(),
  createPreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn(),
  importPreset: vi.fn(),
  exportPreset: vi.fn(),
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
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { key: 'orderProcess' }, path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

const SHOT_DIR = 'shots'

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { ru, en, kz } })
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
})

function attachHost(): HTMLElement {
  const host = document.createElement('div')
  host.style.width = '1200px'
  host.style.minHeight = '700px'
  host.style.padding = '24px'
  host.style.background = '#fff'
  document.body.appendChild(host)
  hosts.push(host)
  return host
}

const SAMPLE_ROWS: PresetVariable[] = [
  { name: 'createdEmployeeId', type: 'LONG', value: '42' },
  { name: 'sourceUuid', type: 'UUID', value: '{{uuid}}' },
  { name: 'stages', type: 'JSON', value: '{"a":[1,2]}' },
  { name: 'approved', type: 'BOOLEAN', value: 'true' },
  { name: 'comment', type: 'STRING', value: '' },
  { name: 'needInput', type: 'LONG', value: '' },
]

describe('WO-VT-1 screenshots (real Chromium)', () => {
  it('VariablesEditor with all row kinds and ask-at-launch highlight', async () => {
    const i18n = makeI18n()
    const wrapper = mount(VariablesEditor, {
      attachTo: attachHost(),
      props: { modelValue: SAMPLE_ROWS },
      global: { plugins: [createPinia(), i18n] },
    })
    mounted.push(wrapper)
    await flushPromises()

    const rows = document.querySelectorAll('[id^="pv-name-"]')
    expect(rows.length).toBe(SAMPLE_ROWS.length)
    const rect = (wrapper.element as Element).getBoundingClientRect()
    expect(rect.width).toBeGreaterThan(500)
    expect(rect.height).toBeGreaterThan(100)
    await page.screenshot({ path: `${SHOT_DIR}/wo-vt-1-variables-editor.png` })
  })

  it('PresetEditorDialog in create mode with prefilled rows', async () => {
    const i18n = makeI18n()
    const wrapper = mount(PresetEditorDialog, {
      attachTo: attachHost(),
      props: {
        open: true,
        processKey: 'orderProcess',
        targetKind: 'START',
        targetRef: null,
        initialVariables: SAMPLE_ROWS.slice(0, 3),
      },
      global: { plugins: [createPinia(), i18n] },
    })
    mounted.push(wrapper)
    await flushPromises()

    const dialog = document.querySelector('[role="dialog"]')
    expect(dialog).toBeTruthy()
    const rect = (dialog as Element).getBoundingClientRect()
    expect(rect.width).toBeGreaterThan(400)
    await page.screenshot({ path: `${SHOT_DIR}/wo-vt-1-preset-editor.png` })
  })

  it('StartForm without form-js: manual/template switch renders', async () => {
    const i18n = makeI18n()
    const wrapper = mount(StartForm, {
      attachTo: attachHost(),
      global: { plugins: [createPinia(), i18n] },
    })
    mounted.push(wrapper)
    await flushPromises()

    // Вкладки пикера (Ручной ввод | Из шаблона) + вкладки редактора
    // (Таблица | Сырой JSON) — обе пары рендерятся.
    const tabs = document.querySelectorAll('[role="tab"]')
    expect(tabs.length).toBe(4)
    const rect = (wrapper.element as Element).getBoundingClientRect()
    expect(rect.width).toBeGreaterThan(300)
    await page.screenshot({ path: `${SHOT_DIR}/wo-vt-1-start-form.png` })
  })
})
