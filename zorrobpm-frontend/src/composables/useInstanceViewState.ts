import { ref, watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * WO-UI-25 ДОПОЛНЕНИЕ 2026-10-09 (критерий 8): «где я» живёт в адресе.
 *
 * Баг владельца: находясь в подпроцессе (drill-down плоскость диаграммы),
 * после обновления страницы «выкидывает в основной». Корень: активная
 * вкладка, выбранный элемент, плоскость drill-down и страница активностей —
 * локальные ref, F5 их убивает, а «Обновить» (reloadAll) пересоздаёт viewer.
 *
 * Композабл держит эти четыре значения СИНХРОННО с query URL:
 *  - ?tab=history — активная вкладка (дефолт bpmn в адресе не пишется);
 *  - ?element=Sub_Task — выбранный элемент диаграммы;
 *  - ?plane=SubProcess_1 — плоскость drill-down (id фигуры подпроцесса);
 *  - ?page=1 — сколько страниц активностей сверх первой подгружено.
 * Запись — через router.replace (историю браузера не засоряем: back/forward
 * ходят между инстансами/страницами, а не между кликами по табам), чтение —
 * при монтировании и при смене маршрута (back/forward восстанавливают вид).
 */
export type InstanceTabId =
  | 'bpmn' | 'variables' | 'tasks' | 'serviceTasks'
  | 'incidents' | 'history' | 'subprocesses'

export const INSTANCE_TABS: readonly InstanceTabId[] = [
  'bpmn', 'variables', 'tasks', 'serviceTasks',
  'incidents', 'history', 'subprocesses',
]

// WO-VT-3 раунд 2 (E-VT3-1, пересадка на UI-25): вкладки «Шаблоны» на инстансе
// больше нет — старый ?tab=presets редиректим на ?tab=variables (остальные
// параметры адреса не теряем). UI-25: «мусор в query → дефолт»; VT-3:
// редирект на variables — побеждает VT-3 (решение CTO по пересадке).
const LEGACY_TAB_REDIRECTS: Record<string, InstanceTabId> = {
  presets: 'variables',
}

const DEFAULT_TAB: InstanceTabId = 'bpmn'

function singleParam(value: unknown): string | null {
  if (Array.isArray(value)) return value.length ? String(value[0]) : null
  if (value === undefined || value === null || value === '') return null
  return String(value)
}

export function useInstanceViewState() {
  const route = useRoute()
  const router = useRouter()

  const tab = ref<InstanceTabId>(DEFAULT_TAB)
  const element = ref<string | null>(null)
  const plane = ref<string | null>(null)
  /** Номер СЛЕДУЮЩЕЙ страницы активностей (0 = загружена только первая). */
  const activitiesPage = ref(0)

  // Цикл «query→ref→query» разрываем флагом: программная запись query не
  // должна перечитываться обратно как «пользователь сменил адрес».
  let writing = false

  function readFromQuery(): void {
    const q = route.query ?? {}
    const t = singleParam(q.tab)
    if (t !== null && !(INSTANCE_TABS as readonly string[]).includes(t)) {
      // Устаревшая вкладка (напр. ?tab=presets после WO-VT-3): редирект на
      // замену БЕЗ потери остальных параметров (?element/?plane/?page целы).
      const target = LEGACY_TAB_REDIRECTS[t]
      tab.value = target ?? DEFAULT_TAB
      if (target) {
        const replace = (router as Partial<typeof router>).replace
        if (typeof replace === 'function') {
          writing = true
          void replace
            .call(router, { query: { ...q, tab: target } })
            .finally(() => {
              writing = false
            })
        }
      }
    } else {
      tab.value = t !== null ? (t as InstanceTabId) : DEFAULT_TAB
    }
    element.value = singleParam(q.element)
    plane.value = singleParam(q.plane)
    const p = singleParam(q.page)
    const n = p === null ? 0 : Number.parseInt(p, 10)
    activitiesPage.value = Number.isInteger(n) && n > 0 ? n : 0
  }

  function writeToQuery(): void {
    const next: Record<string, string> = {}
    if (tab.value !== DEFAULT_TAB) next.tab = tab.value
    if (element.value) next.element = element.value
    if (plane.value) next.plane = plane.value
    if (activitiesPage.value > 0) next.page = String(activitiesPage.value)
    const current = route.query ?? {}
    const same = Object.keys(next).length === Object.keys(current).length
      && Object.entries(next).every(([k, v]) => singleParam(current[k]) === v)
    if (same) return
    // Старые тестовые моки vue-router дают useRouter без replace — вид тогда
    // просто живёт в ref, адрес не трогаем (прод-router replace имеет всегда).
    const replace = (router as Partial<typeof router>).replace
    if (typeof replace !== 'function') return
    writing = true
    void replace.call(router, { query: next }).finally(() => {
      writing = false
    })
  }

  readFromQuery()

  // Локальное изменение вида → адрес (replace, не push).
  watch([tab, element, plane, activitiesPage], writeToQuery)

  // Смена маршрута извне (back/forward, прямой переход, deep link) → вид.
  // query той же страницы при навигации pi-1 → sub-1 пустой — вид честно
  // сбрасывается; back возвращает сохранённый query — вид восстанавливается.
  watch(
    () => route.fullPath,
    () => {
      if (!writing) readFromQuery()
    },
  )

  return {
    tab: tab as Ref<InstanceTabId>,
    element,
    plane,
    activitiesPage,
  }
}
