<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import PresetEditorDialog from './PresetEditorDialog.vue'
import type { VariablePreset } from '@/types/presets'
import { listPresets, createPreset, deletePreset, isPresetsDisabled } from '@/services/presetService'
import { errorMessage } from '@/shared/lib/utils'
import { useToast } from '@/composables/useToast'

/**
 * WO-VT-1 (фронт, VT-3): менеджер шаблонов ключа процесса — таблица всех
 * шаблонов с фильтром по виду, правка/удаление. При выключенном флаге
 * прячется целиком (п.6 WO).
 */
const props = defineProps<{
  processKey: string
  /**
   * WO-VT-3 раунд 2 (решение CTO п.4): id элемента → имя из схемы.
   * Неизвестный ref — id как fallback (см. refLabel).
   */
  elementNames?: Record<string, string>
}>()

const { t } = useI18n()
const toast = useToast()

const presets = ref<VariablePreset[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const available = ref(true)
const kindFilter = ref('')
const editingId = ref<string | null>(null)
const dialogOpen = ref(false)
const confirmDeleteId = ref<string | null>(null)

// WO-ACL-11 criterion 11: виды — техническими токенами в value, подписью
// через t() (текстовые <option>START</option> флагит сканер непереведённых).
const KINDS = ['START', 'USER_TASK', 'SERVICE_TASK', 'MESSAGE', 'INCIDENT', 'DMN', 'ADHOC_JOB'] as const

function kindLabel(kind: string): string {
  return t(`presetKind_${kind}`)
}

function visibilityLabel(visibility: string): string {
  return visibility === 'PROCESS' ? t('presetVisibilityProcess') : t('presetVisibilityPrivate')
}

// WO-VT-3 Дополнение №2 п.2 + решение CTO п.4 (раунд 2): «Привязка» →
// «Элемент»: имя элемента из схемы, неизвестный ref — id как fallback,
// для START — «Запуск процесса» вместо «—».
function refLabel(p: VariablePreset): string {
  if (p.targetKind === 'START') return t('presetStartTargetRef')
  if (!p.targetRef) return '—'
  return props.elementNames?.[p.targetRef] || p.targetRef
}

async function load() {
  if (!props.processKey) return
  loading.value = true
  loadError.value = null
  try {
    presets.value = await listPresets({ key: props.processKey })
  } catch (e) {
    if (isPresetsDisabled(e)) {
      available.value = false
      return
    }
    loadError.value = errorMessage(e, t('loadError'))
  } finally {
    loading.value = false
  }
}

function filtered(): VariablePreset[] {
  if (!kindFilter.value) return presets.value
  return presets.value.filter((p) => p.targetKind === kindFilter.value)
}

function openEdit(p: VariablePreset) {
  editingId.value = p.id
  dialogOpen.value = true
}

async function remove(p: VariablePreset) {
  if (confirmDeleteId.value !== p.id) {
    confirmDeleteId.value = p.id
    return
  }
  confirmDeleteId.value = null
  // WO-VT-3 Дополнение №2 п.4: «Отменить» в тосте — пересоздаём удалённый
  // шаблон теми же данными (имя свободно — старый удалён).
  const backup = {
    processDefinitionKey: p.processDefinitionKey,
    targetKind: p.targetKind,
    targetRef: p.targetRef,
    name: p.name,
    description: p.description,
    visibility: p.visibility,
    variables: (p.variables ?? []).map((v) => ({ ...v })),
  }
  try {
    await deletePreset(p.id)
    toast.success(t('presetDeleted'), {
      action: {
        label: t('presetUndo'),
        onClick: () => {
          void createPreset(backup).then(() => load())
        },
      },
    })
    await load()
  } catch (e) {
    toast.error(errorMessage(e, t('presetDeleteFailed')))
  }
}

watch(() => props.processKey, load, { immediate: true })
defineExpose({ reload: load, available })
</script>

<template>
  <div v-if="available" class="border border-border rounded-lg overflow-hidden bg-card">
    <div class="px-4 py-3 border-b border-border flex items-center gap-3 flex-wrap">
      <h3 class="text-sm font-bold">{{ t('presetManagerTitle') }}</h3>
      <span class="flex-1" />
      <label for="preset-kind-filter" class="sr-only">{{ t('presetWhereUsed') }}</label>
      <select id="preset-kind-filter" v-model="kindFilter" class="px-2 py-1 border border-input rounded text-xs font-mono h-8">
        <option value="">{{ t('presetAllKinds') }}</option>
        <option v-for="k in KINDS" :key="k" :value="k">{{ kindLabel(k) }}</option>
      </select>
    </div>
    <!-- WO-VT-3 Дополнение №2 п.2: строка «зачем это». -->
    <p class="px-4 pt-2 text-xs text-muted-foreground">{{ t('presetWhyManager') }}</p>
    <div v-if="loading" class="px-4 py-3 text-sm text-muted-foreground">{{ t('loading') }}</div>
    <p v-else-if="loadError" class="px-4 py-3 text-sm text-red-500">{{ loadError }}</p>
    <div v-else-if="filtered().length" class="overflow-x-auto">
    <table class="w-full text-sm min-w-[560px]">
      <thead class="bg-muted">
        <tr>
          <th class="px-4 py-2 text-left font-medium">{{ t('presetName') }}</th>
          <th class="px-4 py-2 text-left font-medium">{{ t('presetWhereUsed') }}</th>
          <th class="px-4 py-2 text-left font-medium">{{ t('presetElementName') }}</th>
          <th class="px-4 py-2 text-left font-medium">{{ t('presetVisibility') }}</th>
          <th class="px-4 py-2 text-left font-medium"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in filtered()" :key="p.id" class="border-t border-border">
          <td class="px-4 py-2 max-w-48">
            <span class="block truncate" :title="p.name">{{ p.name }}</span>
            <span v-if="p.favorite" aria-hidden="true"> ★</span>
          </td>
          <td class="px-4 py-2 text-xs">{{ kindLabel(p.targetKind) }}</td>
          <td class="px-4 py-2 font-mono text-xs max-w-40 truncate" :title="refLabel(p)">{{ refLabel(p) }}</td>
          <td class="px-4 py-2 text-xs">{{ visibilityLabel(p.visibility) }}</td>
          <td class="px-4 py-2 text-right text-xs whitespace-nowrap">
            <button type="button" class="text-primary hover:underline mr-2 h-8 px-1" @click="openEdit(p)">
              {{ t('presetEdit') }}
            </button>
            <button type="button" class="text-red-500 hover:underline h-8 px-1" @click="remove(p)">
              {{ confirmDeleteId === p.id ? t('presetConfirmDelete') : t('presetDelete') }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    </div>
    <p v-else class="px-4 py-3 text-sm text-muted-foreground">{{ t('presetNoTemplatesHint') }}</p>
    <PresetEditorDialog
      :open="dialogOpen"
      :process-key="processKey"
      target-kind="START"
      :target-ref="null"
      :preset-id="editingId"
      @close="dialogOpen = false"
      @saved="dialogOpen = false; load()"
      @deleted="dialogOpen = false; load()"
    />
  </div>
</template>
