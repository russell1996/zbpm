// @vitest-environment jsdom
/**
 * WO-UI-37 (часть B): панель переменных по скоупам на вкладке «Переменные»
 * страницы инстанса.
 *
 * Контракт (WO):
 *  - группы рендерятся и подписаны: root-группа (scope == null) — «Процесс
 *    (root)», скоуп-группа — «элемент · статус» (дублирующиеся элементы
 *    нумеруются «инстанс N» — у MI-подпроцесса на каждый инстанс свой скоуп
 *    с тем же bpmnElementId);
 *  - переменные внутри — теми же строками, что root-таблица (name/type/value,
 *    длинное значение/JSON — кнопка «Показать полностью» → страница открывает
 *    тот же инспектор, что и для root);
 *  - статус скоупа — через StatusBadge (WO-ACL-11: единственный badge-комп);
 *  - осиротевший скоуп (activity-строка удалена, bpmnElementId == null) —
 *    fallback на короткий activityId, не «undefined»;
 *  - пустая группа — строка «нет переменных».
 *
 * Мутации (каждая обязана краснить, см. отчёт):
 *  M1 — убрать нумерацию дублирующихся элементов → T4;
 *  M2 — кнопка «показать полностью» не эмитит inspect → T3;
 *  M3 — осиротевший скоуп без fallback на activityId → T5.
 */
import { describe, it, expect, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import VariablesByScope from './VariablesByScope.vue'
import type { ProcessVariable, VariableScopeGroup } from '@/types/api'

// i18n-мок возвращает ключ, но ПИШЕТ вызовы — T4 проверяет номера инстансов
// (n=1/n=2), а не только факт нумерации.
export const tCalls: Array<[string, Record<string, unknown> | undefined]> = []
vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (k: string, p?: Record<string, unknown>) => {
      tCalls.push([k, p])
      return k
    },
    locale: { value: 'en' },
  }),
}))

function makeVariable(overrides: Partial<ProcessVariable> = {}): ProcessVariable {
  return {
    name: 'item',
    value: 'a',
    type: 'STRING',
    ...overrides,
  }
}

function rootGroup(vars: ProcessVariable[] = []): VariableScopeGroup {
  return { scope: null, variables: vars }
}

function scopeGroup(
  overrides: Partial<NonNullable<VariableScopeGroup['scope']>> = {},
  vars: ProcessVariable[] = [],
): VariableScopeGroup {
  return {
    scope: {
      activityId: 'act-scope-1',
      bpmnElementId: 'miSub',
      elementType: 'SUB_PROCESS',
      status: 'COMPLETED',
      parentScopeActivityId: null,
      ...overrides,
    },
    variables: vars,
  }
}

function mountPanel(groups: VariableScopeGroup[]): VueWrapper {
  return mount(VariablesByScope, { props: { groups } })
}

describe('WO-UI-37 part B: VariablesByScope panel', () => {
  it('T1: renders root group labeled processRootGroup and scope group labeled element + status', () => {
    const wrapper = mountPanel([
      rootGroup([makeVariable({ name: 'items', type: 'JSON', value: '["a","b"]' })]),
      scopeGroup({}, [makeVariable()]),
    ])

    const root = wrapper.find('[data-testid="scope-group-root"]')
    expect(root.exists()).toBe(true)
    expect(root.text()).toContain('processRootGroup')

    const scope = wrapper.find('[data-testid="scope-group-act-scope-1"]')
    expect(scope.exists()).toBe(true)
    expect(scope.text()).toContain('miSub')
    // статус — через StatusBadge (в i18n-моке отдаёт ключ statusCompleted)
    expect(scope.text()).toContain('statusCompleted')
    // root-группа статуса не имеет
    expect(root.text()).not.toContain('statusCompleted')
  })

  it('T2: variables render as rows with name/type/value (same rows as the root table)', () => {
    const wrapper = mountPanel([
      rootGroup([makeVariable({ name: 'items', type: 'JSON', value: '["a","b"]' })]),
      scopeGroup({}, [makeVariable({ name: 'loopCounter', type: 'LONG', value: '2' })]),
    ])

    const scope = wrapper.find('[data-testid="scope-group-act-scope-1"]')
    expect(scope.text()).toContain('loopCounter')
    expect(scope.text()).toContain('LONG')
    expect(scope.text()).toContain('2')
    // root-строки тоже на месте
    expect(wrapper.find('[data-testid="scope-group-root"]').text()).toContain('items')
  })

  it('T3: long/JSON value gets a showFullValue button that emits inspect with the variable', async () => {
    const big = makeVariable({ name: 'bigJson', type: 'JSON', value: '{"k":"' + 'x'.repeat(200) + '"}' })
    const wrapper = mountPanel([scopeGroup({}, [big])])

    const btn = wrapper.find('[data-testid="scope-inspect-bigJson"]')
    expect(btn, 'long value must offer the full-value button').toBeTruthy()
    expect(btn.text()).toContain('showFullValue')

    await btn.trigger('click')
    const emitted = wrapper.emitted('inspect')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toEqual({ name: 'bigJson', type: 'JSON', value: big.value })

    // короткое STRING-значение кнопки не имеет
    const short = mountPanel([scopeGroup({}, [makeVariable({ name: 'small', value: 'v' })])])
    expect(short.find('[data-testid="scope-inspect-small"]').exists()).toBe(false)
  })

  it('T4: duplicated element ids get 1-based instance numbers in response order', () => {
    const wrapper = mountPanel([
      rootGroup(),
      scopeGroup({ activityId: 'act-1' }, []),
      scopeGroup({ activityId: 'act-2' }, []),
    ])

    // i18n-мок возвращает ключ: нумерация видна как ключ scopeInstance.
    // Оба дубля нумеруются; порядок ответа = порядок создания activity-строк.
    const g1 = wrapper.find('[data-testid="scope-group-act-1"]').text()
    const g2 = wrapper.find('[data-testid="scope-group-act-2"]').text()
    expect(g1).toContain('miSub')
    expect(g2).toContain('miSub')
    expect(g1).toContain('scopeInstance')
    expect(g2).toContain('scopeInstance')

    // Номера именно 1 и 2 (root-группа в счёт не идёт, дубли идут в порядке
    // ответа). До этого теста scopeInstance нигде не вызывается.
    const ns = tCalls
      .filter(([k]) => k === 'scopeInstance')
      .map(([, p]) => p?.['n'])
    expect(ns.sort((a, b) => Number(a) - Number(b))).toEqual([1, 2])

    // а одиночный элемент — БЕЗ нумерации
    tCalls.length = 0
    const single = mountPanel([rootGroup(), scopeGroup({})])
    expect(single.find('[data-testid="scope-group-act-scope-1"]').text()).not.toContain('scopeInstance')
    expect(tCalls.filter(([k]) => k === 'scopeInstance')).toHaveLength(0)
  })

  it('T5: orphaned scope (bpmnElementId null) falls back to the short activityId', () => {
    const wrapper = mountPanel([
      scopeGroup({ activityId: 'a1b2c3d4e5f6a7b8', bpmnElementId: null, status: null }, []),
    ])
    const scope = wrapper.find('[data-testid="scope-group-a1b2c3d4e5f6a7b8"]')
    expect(scope.text()).toContain('a1b2c3d4')
    expect(scope.text()).not.toContain('undefined')
    // без статуса — fallback-подпись, а не «CREATED» (StatusBadge лгал бы)
    expect(scope.text()).toContain('noStatus')
    expect(scope.text()).not.toContain('statusCreated')
  })

  it('T6: empty group shows the noVariables row', () => {
    const wrapper = mountPanel([rootGroup(), scopeGroup({}, [])])
    expect(wrapper.find('[data-testid="scope-group-root"]').text()).toContain('noVariables')
    expect(wrapper.find('[data-testid="scope-group-act-scope-1"]').text()).toContain('noVariables')
  })
})
