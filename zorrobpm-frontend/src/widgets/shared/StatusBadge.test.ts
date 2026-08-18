// @vitest-environment jsdom
/**
 * WO-ACL-11 criterion 13: statuses in every section are translated and shown
 * by ONE component. StatusBadge is the single implementation — pages no longer
 * carry their own badge markup/colors (grep for inline `rounded-full text-xs
 * font-medium` status spans shows only non-status pills: version numbers, roles).
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import StatusBadge from './StatusBadge.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'ru',
  messages: {
    ru: {
      statusApproved: 'Одобрена', statusRejected: 'Отклонена', statusPending: 'На рассмотрении',
      running: 'Выполняется', statusActive: 'Активная', statusInProgress: 'Выполняется',
      statusCompleted: 'Завершена', statusCancelled: 'Отменена', statusIncident: 'Инцидент',
      open: 'Открыт', resolved: 'Разрешён', fired: 'Сработал', consumed: 'Обработан', pending: 'Ожидание',
      active: 'Активный', inactive: 'Неактивный',
    },
  },
})

function render(status: string | null, completedAt: string | null = null) {
  return mount(StatusBadge, { props: { status, completedAt }, global: { plugins: [i18n] } })
}

describe('WO-ACL-11 criterion 13: one translated status badge component', () => {
  it('translates task/activity lifecycle statuses', () => {
    expect(render('CREATED').text()).toBe('Активная')
    expect(render('IN_PROGRESS').text()).toBe('Выполняется')
    expect(render('COMPLETED').text()).toBe('Завершена')
    expect(render('CANCELLED').text()).toBe('Отменена')
    expect(render('ERROR').text()).toBe('Инцидент')
    expect(render('ERROR').classes()).toContain('bg-red-100')
  })

  it('translates incident, submission, timer/message and user statuses', () => {
    expect(render('OPEN').text()).toBe('Открыт')
    expect(render('RESOLVED').text()).toBe('Разрешён')
    expect(render('PENDING').text()).toBe('На рассмотрении') // submission: under review
    expect(render('APPROVED').text()).toBe('Одобрена')
    expect(render('REJECTED').text()).toBe('Отклонена')
    expect(render('WAITING').text()).toBe('Ожидание') // timer/message: waiting
    expect(render('FIRED').text()).toBe('Сработал')
    expect(render('CONSUMED').text()).toBe('Обработан')
    expect(render('ACTIVE').text()).toBe('Активный')
    expect(render('INACTIVE').text()).toBe('Неактивный')
  })

  it('falls back to CREATED/COMPLETED from completedAt for legacy payloads', () => {
    expect(render(null, '2026-01-01').text()).toBe('Завершена')
    expect(render(null, null).text()).toBe('Активная')
  })

  it('keeps unknown statuses visible (raw value), never empty', () => {
    expect(render('SUSPENDED').text()).toBe('SUSPENDED')
  })
})