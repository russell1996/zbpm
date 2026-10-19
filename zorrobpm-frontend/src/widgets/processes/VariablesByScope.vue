<script setup lang="ts">
/**
 * WO-UI-37 (часть B): панель переменных по скоупам на вкладке «Переменные»
 * страницы инстанса (режим «Показать переменные скоупов»).
 *
 * Рендерит то, что отдаёт GET /process-instances/{id}/variables-by-scope
 * (WO-C8-51 часть A), побайто: первая группа — root (scope == null),
 * дальше по группе на каждый скоуп. Чистый рендер без fetch — состояние и
 * загрузка живут на странице (единый источник, тот же quiet-контракт UI-32).
 *
 * Подписи групп:
 *  - root — «Процесс (root)»;
 *  - скоуп — «элемент · [инстанс N] · статус»; у MI-подпроцесса на каждый
 *    инстанс свой скоуп с ОДНИМ И тем же bpmnElementId, поэтому дублирующиеся
 *    элементы нумеруются в порядке ответа (= порядок создания activity-строк);
 *  - осиротевший скоуп (activity-строка удалена, bpmnElementId == null) —
 *    короткий activityId, не «undefined».
 *
 * Строки переменных — те же, что root-таблица (name/type/value; длинное/JSON
 * — кнопка «Показать полностью» через событие `inspect`, её обслуживает
 * page-level инспектор страницы). Статус — StatusBadge (WO-ACL-11: единый
 * badge-компонент), без статуса — нейтральный fallback, а не «CREATED».
 *
 * G24: только примитивы @/components/ui/* (Badge, Button), без голых тегов.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'
import { isLongVariableValue } from '@/shared/lib/variableDisplay'
import type { ProcessVariable, VariableScopeGroup } from '@/types/api'

const props = defineProps<{
  groups: VariableScopeGroup[]
}>()

const emit = defineEmits<{
  (e: 'inspect', variable: ProcessVariable): void
}>()

const { t } = useI18n()

// Сколько групп несёт каждый bpmnElementId — для нумерации MI-инстансов.
const elementCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const g of props.groups) {
    const el = g.scope?.bpmnElementId
    if (el) counts.set(el, (counts.get(el) || 0) + 1)
  }
  return counts
})

function groupLabel(group: VariableScopeGroup): string {
  if (!group.scope) return t('processRootGroup')
  const scope = group.scope
  const name = scope.bpmnElementId ?? scope.activityId.slice(0, 8)
  if (scope.bpmnElementId) {
    const total = elementCounts.value.get(scope.bpmnElementId) ?? 1
    if (total > 1) {
      // 1-based среди ДУБЛЁВ (не по всему списку — root-группа не считается);
      // порядок ответа = порядок создания activity-строк (бэкенд).
      const siblings = props.groups.filter(
        (g) => g.scope?.bpmnElementId === scope.bpmnElementId,
      )
      const n =
        siblings.findIndex((g) => g.scope?.activityId === scope.activityId) + 1
      return `${name} · ${t('scopeInstance', { n })}`
    }
  }
  return name
}

// Подписи вычисляем ОДИН раз на рендер (не в шаблоне по два раза —
// :title + {{ }} дублировали t()-вызовы и ломали детерминированные
// счётчики в тестах).
const renderedGroups = computed(() =>
  props.groups.map((group) => ({
    group,
    testId: group.scope ? group.scope.activityId : 'root',
    label: groupLabel(group),
  })),
)
</script>

<template>
  <div class="space-y-4">
    <div
      v-for="rg in renderedGroups"
      :key="rg.testId"
      class="border border-border rounded-lg overflow-hidden"
      :data-testid="`scope-group-${rg.testId}`"
    >
      <div class="flex items-center gap-2 px-4 py-2 bg-muted/50 min-w-0">
        <span class="text-sm font-medium font-mono truncate" :title="rg.label">
          {{ rg.label }}
        </span>
        <StatusBadge v-if="rg.group.scope && rg.group.scope.status" :status="rg.group.scope.status" />
        <Badge
          v-else-if="rg.group.scope && !rg.group.scope.status"
          variant="secondary"
          data-testid="scope-status-unknown"
        >
          {{ t('noStatus') }}
        </Badge>
      </div>
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('type') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('value') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in rg.group.variables" :key="v.name" class="border-t border-border">
            <td class="px-4 py-3 font-mono">{{ v.name }}</td>
            <td class="px-4 py-3">
              <Badge variant="secondary" class="font-mono">{{ v.type }}</Badge>
            </td>
            <td class="px-4 py-3 max-w-xs" :title="v.value">
              <span class="block truncate">{{ v.value }}</span>
              <Button
                v-if="isLongVariableValue(v)"
                type="button"
                variant="link"
                size="sm"
                class="mt-1 h-auto p-0 text-xs shrink-0"
                :data-testid="`scope-inspect-${v.name}`"
                @click.stop="emit('inspect', v)"
              >
                {{ t('showFullValue') }}
              </Button>
            </td>
          </tr>
          <tr v-if="!rg.group.variables.length">
            <td colspan="3" class="px-4 py-6 text-center text-muted-foreground">{{ t('noVariables') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
