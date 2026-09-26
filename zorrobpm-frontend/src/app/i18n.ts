import { createI18n } from 'vue-i18n'
import ru from '../locales/ru.json'
import en from '../locales/en.json'
import kz from '../locales/kz.json'

const savedLocale = localStorage.getItem('zbpm_locale') || 'ru'

const i18n = createI18n({
  legacy: false,
  locale: savedLocale,
  fallbackLocale: 'en',
  messages: { ru, en, kz },
})

export function setLocale(locale: string) {
  ;(i18n.global.locale as { value: string }).value = locale
  localStorage.setItem('zbpm_locale', locale)
  document.documentElement.lang = locale
}

export default i18n
