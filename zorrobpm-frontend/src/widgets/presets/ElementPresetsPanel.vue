<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import PresetEditorDialog from './PresetEditorDialog.vue'
import VariableRowMenu from './VariableRowMenu.vue'
import type {
  PresetTargetKind,
  PresetVariable,
  VariablePreset,
} from '@/types/presets'
import {
  listPresets,
  createPreset,
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
  dialogOpen.value = true
}

function openEdit(p: VariablePreset) {
  editingId.value = p.id
  dialogOpen.value = true
}

// WO-UI-27 доп.1–2: удаление шаблона места — в один клик, без confirm;
// защита — тихий undo-тост (пересоздание теми же данными).
async function remove(p: VariablePreset) {
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

function visibilityLabel(visibility: string): string {
  return visibility === 'PROCESS' ? t('presetVisibilityProcess') : t('presetVisibilityPrivate')
}

function onRowMenu(p: VariablePreset, id: string) {
  if (id === 'edit') openEdit(p)
  else if (id === 'export') void exportOne(p)
  else if (id === 'delete') void remove(p)
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
  void load()
}

function onDeleted() {
  editingId.value = null
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
      <!-- WO-VT-3 (критерий 7): карточка вместо flex-строки из 4 действий:
           клик = Изменить, действия — под ⋯, удаление с подтверждением. -->
      <li
        v-for="p in presets"
        :key="p.id"
        class="rounded-md border border-border px-2 py-1.5"
        data-testid="preset-card"
      >
        <div class="flex items-center gap-1.5 min-w-0">
          <Button
            type="button"
            variant="ghost"
            size="icon"
            class="text-sm leading-none shrink-0"
            :aria-label="t('presetFavorite')"
            :aria-pressed="p.favorite ? 'true' : 'false'"
            :title="t('presetFavorite')"
            @click="toggleFavorite(p)"
          >
            {{ p.favorite ? '★' : '☆' }}
          </Button>
          <Button
            type="button"
            variant="ghost"
            class="flex-1 min-w-0 justify-start rounded px-1 py-1 h-auto"
            :title="p.name"
            @click="openEdit(p)"
          >
            <span class="block truncate text-xs font-medium">{{ p.name }}</span>
            <span class="mt-0.5 flex items-center gap-1 text-[11px] text-muted-foreground">
              <span
                class="inline-flex items-center rounded-full border border-border px-1.5 py-px"
                :title="t('presetVisibility')"
              >
                {{ visibilityLabel(p.visibility) }}
              </span>
              <span>{{ t('presetVarCount', { n: p.variables?.length ?? 0 }) }}</span>
            </span>
          </Button>
          <VariableRowMenu
            :label="t('presetRowMenu')"
            :items="[
              { id: 'edit', label: t('presetEdit') },
              { id: 'export', label: t('presetExport') },
              { id: 'delete', label: t('presetDelete'), danger: true },
            ]"
            @select="onRowMenu(p, $event)"
          />
        </div>
      </li>
    </ul>
    <div v-else class="rounded-md border border-dashed border-border px-2 py-3 text-center">
      <p class="text-xs text-muted-foreground">{{ t('presetNoTemplatesHint') }}</p>
      <Button
        type="button"
        size="sm"
        class="mt-1.5 w-full text-xs"
        @click="openCreate"
      >
        {{ t('presetAddTemplate') }}
      </Button>
    </div>
    <div v-if="presets.length" class="flex flex-wrap gap-1.5">
      <Button
        type="button"
        size="sm"
        class="flex-1 whitespace-nowrap text-xs"
        @click="openCreate"
      >
        {{ t('presetCreateNew') }}
      </Button>
      <Button
        type="button"
        variant="outline"
        size="sm"
        class="whitespace-nowrap text-xs"
        @click="triggerImport"
      >
        {{ t('presetImportFile') }}
      </Button>
    </div>
    <input ref="fileInput" type="file" accept="application/json,.json" class="hidden" @change="onFileChosen" />
    <PresetEditorDialog
      :open="dialogOpen"
      :process-key="processKey"
      :target-kind="targetKind"
      :target-ref="targetRef"
      :preset-id="editingId"
      @close="dialogOpen = false"
      @saved="onSaved"
      @deleted="onDeleted"
    />
  </div>
</template>
