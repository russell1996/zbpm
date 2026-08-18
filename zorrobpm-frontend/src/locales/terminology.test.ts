// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 1-2: one name for the section.
 *  1 — no user-visible "Определение(я) процесс(ов)" anywhere in the locales;
 *  2 — the menu, the page header and the breadcrumb call the section the same
 *      way: "Схемы процессов" (singular "Схема процесса").
 */
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ru from './ru.json'
import en from './en.json'
import kz from './kz.json'
import SidebarNav from '@/widgets/shared/SidebarNav.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/processes/definitions' }),
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: true }),
}))

const i18n = createI18n({ legacy: false, locale: 'ru', fallbackLocale: 'en', messages: { en, ru, kz } })

describe('WO-ACL-11 criteria 1-2: "Схемы процессов" everywhere', () => {
  it('criterion 1: ru.json contains no "Определени" (definition) anywhere', () => {
    // case-insensitive: the verifier found "определения" (lowercase) in
    // filterByDefId, bpmnNotAvailable, noStartEvents, viewDefinition
    for (const [key, value] of Object.entries(ru)) {
      expect(String(value).toLowerCase(), `ru.${key}`).not.toContain('определени')
    }
    expect(ru.processDefinitions).toBe('Схемы процессов')
    expect(ru.processDefinition).toBe('Схема процесса')
    expect(ru.noDefinitions).toBe('Схемы ещё не загружены')
  })

  it('criterion 1: kz.json contains no "анықтама" (definition) anywhere', () => {
    // case-insensitive: "Анықтама" (capitalized) survived the first round
    for (const [key, value] of Object.entries(kz)) {
      expect(String(value).toLowerCase(), `kz.${key}`).not.toContain('анықтама')
    }
    expect(kz.processDefinitions).toBe('Процесс схемалары')
    expect(kz.processDefinition).toBe('Процесс схемасы')
  })

  it('criterion 2: menu key, header key and crumb key carry the SAME ru name', () => {
    // menu item ('definitions'), page header ('processDefinitions') and the
    // crumb parent ('processDefinitions') all resolve to the same string
    expect(ru.definitions).toBe('Схемы процессов')
    expect(ru.processDefinitions).toBe(ru.definitions)
  })

  it('criterion 2: the definitions list header renders t(processDefinitions)', () => {
    // the header text is bound to the key, so the rendered title is
    // "Схемы процессов" — the same string the menu and crumb use
    const raw = import.meta.glob('../pages/processes/ProcessDefinitionList.vue', { query: '?raw', import: 'default', eager: true })
    const source = Object.values(raw)[0] as string
    expect(source).toContain("<h1 class=\"text-2xl font-bold\">{{ t('processDefinitions') }}</h1>")
  })

  it('criterion 2: the sidebar menu item renders "Схемы процессов"', () => {
    const wrapper = mount(SidebarNav, {
      global: { plugins: [createPinia(), i18n] },
    })
    const buttons = wrapper.findAll('button')
    const definitionsBtn = buttons.find((b) => b.text().includes('Схемы процессов'))
    expect(definitionsBtn).toBeTruthy()
  })
})