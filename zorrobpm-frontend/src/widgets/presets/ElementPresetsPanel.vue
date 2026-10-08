<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import PresetEditorDialog from './PresetEditorDialog.vue'
import type {
  PresetTargetKind,
  PresetVariable,
  VariablePreset,
} from '@/types/presets'
import {
  listPresets,
  deletePreset,
  importPreset,
  exportPreset,
  setPresetFavorite,
  isPresetsDisabled,
} from '@/services/presetService'
import { errorMessage } from '@/shared/lib/utils'
import { useToast } from '@/composables/useToast'

/**
 * WO-VT-1 (фронт, §1-бис п.2-бис + VT-4): панель «Шаблоны для этого элемента».
 * Клик по элементу диаграммы: если шаблонов места нет — «Добавить шаблон»;
 * если есть — список с «Изменить / Дублировать / Создать новый / Удалить»,
 * избранное, импорт/экспорт файлом. При выключенном флаге прячется целиком.
 */
const props = defineProps<{
  processKey: string
  targetKind: PresetTargetKind
  targetRef: string | null
  elementName?: string | null
}>()

const { t } = useI18n()
const toast = useToast()

const presets = ref<VariablePreset[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const available = ref(true)
const dialogOpen = ref(false)
const editingId = ref<string | null>(null)
const duplicateOf = ref<VariablePreset | null>(null)
const confirmDeleteId = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const title = computed(() => {
  const where = props.elementName || props.targetRef || t('presetStartEvent')
  return t('presetElementPanelTitle', { element: where })
})

async function load() {
  loading.value = true
  loadError.value = null
  try {
    presets.value = await listPresets({
      key: props.processKey,
      kind: props.targetKind,
      ...(props.targetRef ? { ref: props.targetRef } : {}),
    })
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

function openCreate() {
  editingId.value = null
  duplicateOf.value = null
  dialogOpen.value = true
}

function openEdit(p: VariablePreset) {
  editingId.value = p.id
  duplicateOf.value = null
  dialogOpen.value = true
}

function openDuplicate(p: VariablePreset) {
  editingId.value = p.id
  duplicateOf.value = p
  dialogOpen.value = true
}

async function remove(p: VariablePreset) {
  if (confirmDeleteId.value !== p.id) {
    confirmDeleteId.value = p.id
    return
  }
  try {
    await deletePreset(p.id)
    confirmDeleteId.value = null
    toast.success(t('presetDeleted'))
    await load()
  } catch (e) {
    toast.error(errorMessage(e, t('presetDeleteFailed')))
  }
}

async function toggleFavorite(p: VariablePreset) {
  try {
    await setPresetFavorite(p.id, !p.favorite)
    p.favorite = !p.favorite
  } catch (e) {
    toast.error(errorMessage(e, t('presetFavoriteFailed')))
  }
}

function onSaved() {
  dialogOpen.value = false
  editingId.value = null
  duplicateOf.value = null
  void load()
}

function onDeleted() {
  editingId.value = null
  duplicateOf.value = null
  void load()
}

function downloadFile(name: string, content: string) {
  const blob = new Blob([content], { type: 'application/json;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
}

async function exportOne(p: VariablePreset) {
  try {
    const payload = await exportPreset(p.id)
    downloadFile(`preset-${p.name}.json`, JSON.stringify(payload, null, 2))
  } catch (e) {
    toast.error(errorMessage(e, t('loadError')))
  }
}

function triggerImport() {
  fileInput.value?.click()
}

async function onFileChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  // WO-VT-1 раунд 2 (оформительская №2 рецензии r1): клиентский лимит
  // размера — гигантский JSON вешал вкладку до серверного 400.
  // Лимит 1 МБ с запасом над серверными 256 КБ × 100 переменных.
  if (file.size > 1024 * 1024) {
    toast.error(t('presetImportTooLarge'))
    return
  }
  try {
    const text = await file.text()
    const payload = JSON.parse(text) as {
      processDefinitionKey?: string
      targetKind?: PresetTargetKind
      targetRef?: string | null
      name?: string
      description?: string | null
      visibility?: 'PRIVATE' | 'PROCESS'
      variables?: PresetVariable[]
    }
    await importPreset({
      processDefinitionKey: props.processKey,
      targetKind: props.targetKind,
      targetRef: props.targetRef,
      name: payload.name ?? file.name.replace(/\.json$/, ''),
      description: payload.description ?? null,
      visibility: payload.visibility,
      variables: payload.variables ?? [],
    })
    toast.success(t('presetSaved'))
    await load()
  } catch (err) {
    toast.error(errorMessage(err, t('presetImportFailed')))
  }
}

watch(
  () => [props.processKey, props.targetKind, props.targetRef],
  () => {
    if (available.value) void load()
  },
)

onMounted(() => {
  void load()
})

defineExpose({ reload: load, available })
</script>

<template>
  <div v-if="available" class="pt-2 border-t border-border space-y-2">
    <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ title }}</h4>
    <div v-if="loading" class="text-xs text-muted-foreground">{{ t('loading') }}</div>
    <p v-else-if="loadError" class="text-xs text-red-500">{{ loadError }}</p>
    <ul v-else-if="presets.length" class="space-y-1.5">
      <li
        v-for="p in presets"
        :key="p.id"
        class="flex items-center gap-1.5 text-xs border border-border rounded-md px-2 py-1.5"
      >
        <button
          type="button"
          class="text-sm leading-none"
          :aria-label="t('presetFavorite')"
          :aria-pressed="p.favorite ? 'true' : 'false'"
          @click="toggleFavorite(p)"
        >
          {{ p.favorite ? '★' : '☆' }}
        </button>
        <span class="font-medium truncate flex-1 min-w-0">{{ p.name }}</span>
        <span class="text-muted-foreground shrink-0">({{ p.variables?.length ?? 0 }})</span>
        <button type="button" class="text-primary hover:underline shrink-0" @click="openEdit(p)">
          {{ t('presetEdit') }}
        </button>
        <button type="button" class="text-primary hover:underline shrink-0" @click="openDuplicate(p)">
          {{ t('presetDuplicate') }}
        </button>
        <button type="button" class="text-primary hover:underline shrink-0" @click="exportOne(p)">
          {{ t('presetExport') }}
        </button>
        <button type="button" class="text-red-500 hover:underline shrink-0" @click="remove(p)">
          {{ confirmDeleteId === p.id ? t('presetConfirmDelete') : t('presetDelete') }}
        </button>
      </li>
    </ul>
    <p v-else class="text-xs text-muted-foreground">{{ t('presetNoTemplates') }}</p>
    <div class="flex flex-wrap gap-1.5">
      <button
        type="button"
        class="px-2.5 py-1 text-xs bg-primary text-primary-foreground rounded-md hover:opacity-90"
        @click="openCreate"
      >
        {{ presets.length ? t('presetCreateNew') : t('presetAddTemplate') }}
      </button>
      <button
        type="button"
        class="px-2.5 py-1 text-xs border border-border rounded-md hover:bg-muted"
        @click="triggerImport"
      >
        {{ t('presetImportFile') }}
      </button>
      <input ref="fileInput" type="file" accept="application/json,.json" class="hidden" @change="onFileChosen" />
    </div>
    <PresetEditorDialog
      :open="dialogOpen"
      :process-key="processKey"
      :target-kind="targetKind"
      :target-ref="targetRef"
      :initial-variables="duplicateOf?.variables ? duplicateOf.variables.map((x) => ({ ...x })) : []"
      :preset-id="duplicateOf ? null : editingId"
      :duplicate-name="duplicateOf ? t('presetCopySuffix', { name: duplicateOf.name }) : null"
      @close="dialogOpen = false"
      @saved="onSaved"
      @deleted="onDeleted"
    />
  </div>
</template>
