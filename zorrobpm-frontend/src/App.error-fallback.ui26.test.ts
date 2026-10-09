// @vitest-environment jsdom
/**
 * WO-UI-26 Доп.1 (кр.11) — белый экран устранён.
 *
 * - ошибка рендера/рантайма (reportUiError — тот же путь, что
 *   app.config.errorHandler в main.ts) → вместо пустого экрана понятная
 *   панель [data-testid="ui-error-fallback"] с текстом причины и кнопкой
 *   «Перезагрузить» (shadcn Button, не сырой button);
 * - причина НЕ проглатывается (console.error вызван);
 * - без ошибки — панели нет, роутер-выход на месте.
 * Мутация: убрать чтение lastUiError из App.vue → первый тест красный.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import App from './App.vue'
import { reportUiError, clearUiError } from './services/uiError'
import en from './locales/en.json'
import ru from './locales/ru.json'
import kz from './locales/kz.json'

vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: false }),
}))

vi.mock('vue-router', () => ({
  RouterView: { template: '<div class="router-view-stub" />' },
}))

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en, ru, kz } })

afterEach(() => {
  clearUiError()
  document.body.innerHTML = ''
})

describe('App.vue error fallback (WO-UI-26 кр.11)', () => {
  it('ошибка → понятная панель с причиной и кнопкой перезагрузки, не белый экран', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      reportUiError(new Error('boom-render-failed'))
      const wrapper = mount(App, { attachTo: document.body, global: { plugins: [i18n] } })
      const fallback = wrapper.find('[data-testid="ui-error-fallback"]')
      expect(fallback.exists()).toBe(true)
      // Причина видна пользователю (не проглочена)…
      expect(fallback.text()).toContain('boom-render-failed')
      // …и в консоли (для диагностики).
      expect(errSpy).toHaveBeenCalled()
      // Кнопка — shadcn Button, экран не пуст.
      const reload = wrapper.find('[data-testid="ui-error-reload"]')
      expect(reload.exists()).toBe(true)
      expect(document.body.innerHTML.trim()).not.toBe('')
      expect(document.body.innerHTML).not.toBe('<div id="app"></div>')
      wrapper.unmount()
    } finally {
      errSpy.mockRestore()
    }
  })

  it('без ошибки — панели нет, роутер-выход рендерится', () => {
    const wrapper = mount(App, { attachTo: document.body, global: { plugins: [i18n] } })
    expect(wrapper.find('[data-testid="ui-error-fallback"]').exists()).toBe(false)
    expect(wrapper.find('.router-view-stub').exists()).toBe(true)
    wrapper.unmount()
  })

  it('clearUiError убирает панель (восстановление без перезагрузки)', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      reportUiError(new Error('transient'))
      const wrapper = mount(App, { attachTo: document.body, global: { plugins: [i18n] } })
      expect(wrapper.find('[data-testid="ui-error-fallback"]').exists()).toBe(true)
      clearUiError()
      return wrapper.vm.$nextTick().then(() => {
        expect(wrapper.find('[data-testid="ui-error-fallback"]').exists()).toBe(false)
        wrapper.unmount()
      })
    } finally {
      errSpy.mockRestore()
    }
  })
})
