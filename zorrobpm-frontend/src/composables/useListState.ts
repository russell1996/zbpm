import { computed, getCurrentInstance, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * WO-UI-39 — единый механизм состояния списка.
 *
 * Проблема, которую закрывает этот композабл: фильтры/поиск/страница списка
 * жили в локальном состоянии компонента. Переход в карточку размонтирует
 * список, «Назад» создаёт его заново — состояние терялось.
 *
 * Как работает:
 * - состояние живёт в query-параметрах маршрута (`router.replace` — история
 *   браузера не растёт на каждом клике фильтра), поэтому «Назад» возвращает
 *   ровно то же состояние, а ссылку со списком можно скопировать/открыть заново;
 * - ПАРАМЕТРЫ ПРОСТРАНСТВЕННЫ ПО СПИСКУ: `${key}.${field}` (например
 *   `?instances.key=vacation&instances.page=2`). Состояние одного списка не
 *   протекает в другой;
 * - значения по умолчанию в URL НЕ пишутся (чистый URL: дефолт = отсутствие
 *   параметра);
 * - некорректный/устаревший параметр из URL молча отбрасывается к дефолту
 *   (никаких падений), а URL нормализуется (параметр убирается);
 * - страница: внутренне 0-based (как ожидает бэкенд), в URL 1-based
 *   (`?instances.page=2` = 2-я страница, совпадает с подписью `page + 1`).
 *
 * Композабл работает и без роутера (обычные юнит-тесты): тогда состояние
 * остаётся локальным, URL не трогается — сигнатура вызова не меняется.
 */
export type ListFieldValue = string | number | boolean

/** Описание поля: значение по умолчанию + (необязательно) список допустимых значений. */
export interface ListFieldSpec<V extends ListFieldValue> {
  default: V
  /**
   * Допустимые значения поля. Значение вне списка — «битый параметр» из URL →
   * молча отбрасывается к значению по умолчанию (см. правило WO-UI-39 п.3).
   */
  values?: readonly V[]
}

export interface UseListStateOptions {
  /** Размер страницы (по умолчанию 10). */
  pageSize?: number
  /** Тотал элементов — нужен для `hasNext`. */
  getTotalElements?: () => number | undefined
}

export interface UseListStateResult<F extends Record<string, ListFieldValue>> {
  /** Состояние списка (фильтры/поиск/сортировка) — реактивный объект. */
  state: F
  /** Номер страницы: 0-based внутри, 1-based в URL. */
  page: Ref<number>
  /** Размер страницы (из URL, если задан, иначе из опций). */
  pageSize: number
  hasNext: ComputedRef<boolean>
  hasPrev: ComputedRef<boolean>
  nextPage: () => void
  prevPage: () => void
  resetPage: () => void
  /**
   * WO-UI-41: сброс состояния к дефолтам (поля + страница 1). Query списка
   * очищается тем же watcher'ом, что пишет его при изменении полей.
   */
  reset: () => void
  /**
   * WO-UI-41: состояние совпадает с дефолтным? (все поля = дефолт И страница 1).
   * По этому флагу списки показывают кнопку «Сбросить фильтры».
   */
  isDefault: ComputedRef<boolean>
}

/**
 * WO-UI-41: префикс ключа в sessionStorage, где лежит ПОСЛЕДНИЙ query списка.
 *
 * Зачем: query маршрута живёт в адресе, а карточка открывается по адресу БЕЗ
 * query списка (ссылка «назад к списку» вела на голый маршрут) — фильтры
 * терялись. Здесь список помнит свой последний query, и стрелка «назад»
 * возвращает список именно с ним.
 */
export const LIST_STATE_STORAGE_PREFIX = 'zbpm.listState.'

/** Прочитать сохранённый query списка (только его собственные параметры). */
export function readListQuery(listKey: string): Record<string, string> {
  if (typeof window === 'undefined') return {}
  try {
    const raw = window.sessionStorage.getItem(LIST_STATE_STORAGE_PREFIX + listKey)
    if (!raw) return {}
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    const out: Record<string, string> = {}
    for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v === 'string') out[k] = v
    }
    return out
  } catch {
    // sessionStorage может быть недоступен (приватный режим, переполнение) —
    // это не повод ломать список: просто фильтры не переживут уход в карточку.
    return {}
  }
}

/** Запомнить query списка (только его собственные параметры). */
export function writeListQuery(listKey: string, query: Record<string, string>): void {
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.setItem(LIST_STATE_STORAGE_PREFIX + listKey, JSON.stringify(query))
  } catch {
    // см. readListQuery — ошибка хранилища не должна ломать список
  }
}

interface FieldMeta {
  field: string
  param: string
  default: ListFieldValue
  values?: readonly ListFieldValue[]
  kind: 'string' | 'number' | 'boolean'
}

function kindOf(value: ListFieldValue): FieldMeta['kind'] {
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'number') return 'number'
  return 'string'
}

function metaOf<V extends ListFieldValue>(
  field: string,
  spec: V | ListFieldSpec<V>,
  key: string,
): FieldMeta {
  const isSpec = typeof spec === 'object' && spec !== null && 'default' in spec
  const def = (isSpec ? (spec as ListFieldSpec<V>).default : spec) as ListFieldValue
  const values = isSpec ? (spec as ListFieldSpec<V>).values : undefined
  return {
    field,
    param: `${key}.${field}`,
    default: def,
    values: values as readonly ListFieldValue[] | undefined,
    kind: kindOf(def),
  }
}

/** Разбор значения из URL. Любое несоответствие → значение по умолчанию. */
function parseValue(raw: unknown, meta: FieldMeta): ListFieldValue {
  // Массив (?a=1&a=2) или не-строка — битый параметр.
  if (typeof raw !== 'string') return meta.default
  if (meta.kind === 'boolean') {
    if (raw === '1' || raw === 'true') return true
    if (raw === '0' || raw === 'false') return false
    return meta.default
  }
  if (meta.kind === 'number') {
    if (!/^-?\d+$/.test(raw)) return meta.default
    const n = Number(raw)
    return Number.isSafeInteger(n) ? n : meta.default
  }
  if (meta.values && !meta.values.includes(raw)) return meta.default
  return raw
}

/** Кодирование значения в URL. `null` = параметр должен отсутствовать (дефолт). */
function encodeValue(value: ListFieldValue, meta: FieldMeta): string | null {
  if (value === meta.default) return null
  if (meta.kind === 'boolean') return value ? '1' : '0'
  return String(value)
}

/** 1-based в URL → 0-based внутри. Мусор → 0. */
function parsePageParam(raw: unknown): number {
  if (typeof raw !== 'string') return 0
  if (!/^\d+$/.test(raw)) return 0
  const n = Number(raw)
  return n >= 1 ? n - 1 : 0
}

function sameQuery(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  for (const k of keys) {
    const av = a[k]
    const bv = b[k]
    const aEmpty = av === undefined || av === null
    const bEmpty = bv === undefined || bv === null
    if (aEmpty && bEmpty) continue
    if (aEmpty !== bEmpty) return false
    // Массив в текущем query — «битая» форма: переписываем на каноническую.
    if (Array.isArray(av) || Array.isArray(bv)) return false
    if (String(av) !== String(bv)) return false
  }
  return true
}

export function useListState<F extends Record<string, ListFieldValue>>(
  key: string,
  fields: { [K in keyof F]: F[K] | ListFieldSpec<F[K]> },
  options: UseListStateOptions = {},
): UseListStateResult<F> {
  const metas = (Object.keys(fields) as (keyof F & string)[]).map((field) =>
    metaOf(field, fields[field] as ListFieldValue | ListFieldSpec<ListFieldValue>, key),
  )

  const pageParam = `${key}.page`
  const sizeParam = `${key}.size`

  // --- Роутер необязателен: вне setup-контекста (обычные юнит-тесты) —
  //     состояние остаётся локальным, URL не трогаем.
  const inSetup = getCurrentInstance() !== null
  let route: { query: Record<string, unknown>; name?: unknown; path?: unknown } | null = null
  let router: { replace: (to: unknown) => unknown } | null = null
  if (inSetup) {
    try {
      const r = useRoute() as unknown
      const ro = useRouter() as unknown
      if (
        r !== null &&
        typeof r === 'object' &&
        (r as { query?: unknown }).query !== null &&
        typeof (r as { query?: unknown }).query === 'object' &&
        ro !== null &&
        typeof ro === 'object' &&
        typeof (ro as { replace?: unknown }).replace === 'function'
      ) {
        route = r as { query: Record<string, unknown>; name?: unknown; path?: unknown }
        router = ro as { replace: (to: unknown) => unknown }
      }
    } catch {
      route = null
      router = null
    }
  }
  const synced = route !== null && router !== null
  /**
   * Путь маршрута, на котором список живёт. По нему мы узнаём, что текущий
   * query — ещё НАШ: уход в карточку меняет адрес РАНЬШЕ, чем список
   * размонтируется, и адрес карточки не должен затирать память списка (иначе
   * стрелка «назад» потеряла бы фильтры).
   */
  const listPath = synced ? route!.path : undefined

  // --- Начальные значения: из URL, если он есть, иначе дефолты.
  const initial = {} as Record<string, ListFieldValue>
  for (const meta of metas) {
    const raw = synced ? route!.query[meta.param] : undefined
    initial[meta.field] = synced ? parseValue(raw, meta) : meta.default
  }
  const state = reactive(initial) as F

  const page = ref(synced ? parsePageParam(route!.query[pageParam]) : 0)

  const sizeRaw = synced ? route!.query[sizeParam] : undefined
  const parsedSize =
    typeof sizeRaw === 'string' && /^\d+$/.test(sizeRaw) && Number(sizeRaw) > 0
      ? Number(sizeRaw)
      : undefined
  const pageSize = parsedSize ?? options.pageSize ?? 10

  const hasNext = computed(() => {
    const total = options.getTotalElements?.()
    return total !== undefined && (page.value + 1) * pageSize < total
  })
  const hasPrev = computed(() => page.value > 0)

  function nextPage() {
    if (hasNext.value) page.value++
  }
  function prevPage() {
    if (hasPrev.value) page.value--
  }
  function resetPage() {
    page.value = 0
  }

  /**
   * WO-UI-41: сброс к дефолтам. Поля возвращаются к значениям по умолчанию,
   * страница — к первой. Query списка очищается автоматически: encodeValue()
   * для дефолта возвращает null (параметр удаляется), а для страницы 1
   * параметр `*.page` тоже удаляется.
   */
  function reset() {
    for (const meta of metas) {
      ;(state as Record<string, ListFieldValue>)[meta.field] = meta.default
    }
    page.value = 0
  }

  /** WO-UI-41: состояние == дефолт (поля + первая страница)? */
  const isDefault = computed(() => {
    for (const meta of metas) {
      if ((state[meta.field] as ListFieldValue) !== meta.default) return false
    }
    return page.value === 0
  })

  if (synced) {
    const r = route!
    const ro = router!

    /** Желаемый query: наши параметры + всё, что не наше (чужие фильтры). */
    function desiredQuery(): Record<string, unknown> {
      const query: Record<string, unknown> = { ...r.query }
      for (const meta of metas) {
        const encoded = encodeValue(state[meta.field] as ListFieldValue, meta)
        if (encoded === null) delete query[meta.param]
        else query[meta.param] = encoded
      }
      if (page.value > 0) query[pageParam] = String(page.value + 1)
      else delete query[pageParam]
      if (pageSize !== (options.pageSize ?? 10)) query[sizeParam] = String(pageSize)
      else delete query[sizeParam]
      return query
    }

    /**
     * WO-UI-41: собственные параметры списка из готового query — их и
     * запоминаем в sessionStorage (чужие параметры — не наше дело).
     */
    function ownParams(query: Record<string, unknown>): Record<string, string> {
      const out: Record<string, string> = {}
      const prefix = `${key}.`
      for (const [param, value] of Object.entries(query)) {
        if (param.startsWith(prefix) && typeof value === 'string') out[param] = value
      }
      return out
    }

    /** Запись состояния в URL (replace — история не растёт). */
    function sync() {
      const query = desiredQuery()
      // Сначала запоминаем query списка: карточка открывается по адресу БЕЗ
      // query, и стрелка «назад» возвращает список с последним состоянием.
      writeListQuery(key, ownParams(query))
      if (sameQuery(r.query, query)) return
      void ro.replace({ query })
    }

    // Нормализация URL при монтировании (самопочинка): битые параметры и
    // параметры, равные дефолту, убираются — «чистый URL».
    const canonical = desiredQuery()
    writeListQuery(key, ownParams(canonical))
    if (!sameQuery(r.query, canonical)) {
      void ro.replace({ query: canonical })
    }

    // состояние → URL
    watch([state, page], sync)

    // URL → состояние (кнопка «Назад»/«Вперёд», ручная правка адреса)
    watch(
      () => r.query,
      () => {
        // Мы больше не на маршруте списка (уход в карточку меняет адрес
        // раньше, чем список размонтируется): состояние списка и его память
        // (sessionStorage) трогать нельзя — иначе адрес карточки затрёт
        // фильтры, и стрелка «назад» вернёт список без них.
        if (r.path !== listPath) return
        for (const meta of metas) {
          const parsed = parseValue(r.query[meta.param], meta)
          if ((state[meta.field] as ListFieldValue) !== parsed) {
            ;(state as Record<string, ListFieldValue>)[meta.field] = parsed
          }
        }
        const parsedPage = parsePageParam(r.query[pageParam])
        if (page.value !== parsedPage) page.value = parsedPage
        // Состояние изменилось → watcher [state, page] сам запишет новый
        // query и запомнит его (writeListQuery в sync()).
      },
    )
  }

  return {
    state,
    page,
    pageSize,
    hasNext,
    hasPrev,
    nextPage,
    prevPage,
    resetPage,
    reset,
    isDefault,
  }
}
