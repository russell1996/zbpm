// @vitest-environment node
/**
 * WO-UI-41 — страж проводки: у каждой страницы-списка с фильтрами есть кнопка
 * «Сбросить фильтры», у каждой карточки — общая стрелка «назад».
 *
 * Зачем страж, если есть поведенческие тесты: поведенческий тест доказывает
 * поведение ОДНОГО списка (задачи), а остальные шесть проверяются «косвенно»
 * (страница монтируется в других сценариях). Стража ловит ровно то, что
 * ловят такие «косвенные» проверки: список, из которого забыли убрать/добавить
 * кнопку или забыли протянуть `reset`/`isDefault` из `useListState`.
 *
 * Файлы читаются как текст (`import.meta.glob` с `?raw`) — без node:fs, чтобы
 * typecheck фронта оставался без node-типов (как в `locales/localeKeys.test.ts`).
 */
import { describe, it, expect } from 'vitest'

const sources = import.meta.glob('./pages/**/*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function src(path: string): string {
  const content = sources[path]
  if (content === undefined) throw new Error(`page not found in tree: ${path}`)
  return content
}

/** Списки с фильтрами: страница → ключ состояния в `useListState`. */
const LIST_PAGES: [path: string, listKey: string][] = [
  ['./pages/processes/ProcessInstanceList.vue', 'instances'],
  ['./pages/processes/ProcessDefinitionList.vue', 'definitions'],
  ['./pages/tasks/TaskList.vue', 'tasks'],
  ['./pages/tasks/ServiceTaskList.vue', 'serviceTasks'],
  ['./pages/incidents/IncidentList.vue', 'incidents'],
  ['./pages/admin/UserList.vue', 'users'],
  ['./pages/admin/SubmissionQueue.vue', 'submissions'],
]

/** Карточки: страница → ключ списка + маршрут списка для стрелки «назад». */
const DETAIL_PAGES: [path: string, listKey: string, listRouteName: string][] = [
  ['./pages/processes/ProcessInstanceDetail.vue', 'instances', 'process-instances'],
  ['./pages/tasks/TaskDetail.vue', 'tasks', 'my-tasks'],
  ['./pages/tasks/ServiceTaskDetail.vue', 'serviceTasks', 'service-tasks'],
  ['./pages/incidents/IncidentDetail.vue', 'incidents', 'incidents'],
  ['./pages/processes/ProcessDefinitionDetail.vue', 'definitions', 'process-definitions'],
]

describe('WO-UI-41: проводка кнопки сброса и стрелки «назад»', () => {
  it.each(LIST_PAGES)('%s: кнопка «Сбросить фильтры» подключена', (path, listKey) => {
    const content = src(path)
    // 1) состояние списка разобрано с reset/isDefault
    expect(content, `${path}: useListState должен отдавать reset и isDefault`).toMatch(
      /useListState<[^>]*>\(([\s\S]{0,400})/,
    )
    const destructuring = content.match(/const \{([^}]*)\} = useListState</)
    expect(destructuring, `${path}: не найден разбор useListState`).not.toBeNull()
    expect(destructuring![1], `${path}: из useListState нужен reset`).toMatch(/\breset\b/)
    expect(destructuring![1], `${path}: из useListState нужен isDefault`).toMatch(/\bisDefault\b/)
    // 2) кнопка подключена с правильными биндами
    expect(content, `${path}: нет <ResetFiltersButton>`).toContain('<ResetFiltersButton')
    expect(content, `${path}: кнопка не привязана к isDefault`).toContain(':is-default="isDefault"')
    expect(content, `${path}: кнопка не привязана к resetFilters`).toContain('@reset="resetFilters"')
    // 3) обработчик сброса зовёт reset() и перезагружает список
    expect(content, `${path}: нет обработчика resetFilters`).toMatch(/function resetFilters\(\)/)
    const handler = content.match(/function resetFilters\(\) \{([\s\S]{0,300}?)\n\}/)
    expect(handler, `${path}: тело resetFilters не разобрано`).not.toBeNull()
    expect(handler![1], `${path}: resetFilters обязан звать reset()`).toMatch(/\breset\(\)/)
    expect(handler![1], `${path}: resetFilters обязан перезагрузить список`).toMatch(/void \w+\(\)/)
    // 4) ключ состояния списка — тот же, что у карточки (стрелка «назад»)
    expect(content, `${path}: ключ списка '${listKey}' не найден`).toContain(`'${listKey}'`)
  })

  it.each(DETAIL_PAGES)('%s: общая стрелка «назад» подключена', (path, listKey, listRouteName) => {
    const content = src(path)
    expect(content, `${path}: нет <BackToList>`).toContain('<BackToList')
    expect(content, `${path}: неверный ключ списка у стрелки`).toContain(`list-key="${listKey}"`)
    expect(content, `${path}: неверный маршрут списка у стрелки`).toContain(
      `list-route-name="${listRouteName}"`,
    )
    // старая голая ссылка на маршрут списка больше не должна оставаться
    expect(content, `${path}: осталась голая RouterLink на список`).not.toMatch(
      /<RouterLink[^>]*:to="\{ name: '(process-instances|my-tasks|service-tasks|incidents|process-definitions)' \}"/,
    )
  })
})
