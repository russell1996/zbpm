<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import PresetPicker from './PresetPicker.vue'
import { publishMessage } from '@/services/messagePublishService'
import { errorMessage } from '@/shared/lib/utils'
import { useToast } from '@/composables/useToast'

/**
 * WO-VT-1 (фронт, VT-4): публикация сообщения из шаблона MESSAGE.
 * Имя сообщения и correlationKey вводит пользователь (ref шаблона = имя
 * сообщения); переменные — PresetPicker (ручной ввод или шаблон).
 */
const props = defineProps<{
  processKey: string
  processInstanceId?: string | null
}>()

const { t } = useI18n()
const toast = useToast()

const messageName = ref('')
const correlationKey = ref('')
const pickerRef = ref<InstanceType<typeof PresetPicker> | null>(null)
const askMissing = ref<string[]>([])
const publishing = ref(false)
const result = ref<string | null>(null)

function onPickerChange() {
  askMissing.value = pickerRef.value?.missingAsk ?? []
}

async function publish() {
  if (!messageName.value.trim() || !pickerRef.value || askMissing.value.length || publishing.value) return
  publishing.value = true
  result.value = null
  try {
    const res = await publishMessage({
      messageName: messageName.value.trim(),
      correlationKey: correlationKey.value.trim() || null,
      processInstanceId: props.processInstanceId ?? null,
      variables: pickerRef.value.getVariables().map((v) => ({
        name: v.name,
        type: v.type,
        value: v.value,
      })),
    })
    result.value = JSON.stringify(res)
    toast.success(t('presetMessagePublished'))
  } catch (e) {
    toast.error(errorMessage(e, t('presetMessagePublishFailed')))
  } finally {
    publishing.value = false
  }
}
</script>

<template>
  <div class="border border-border rounded-lg p-4 bg-card space-y-3">
    <h3 class="text-sm font-bold">{{ t('presetPublishMessage') }}</h3>
    <div class="grid grid-cols-2 gap-3">
      <div>
        <label for="msg-name" class="block text-xs font-medium mb-1">{{ t('presetMessageName') }}</label>
        <input
          id="msg-name"
          v-model="messageName"
          class="w-full px-2 py-1.5 border border-input rounded text-sm font-mono"
        />
      </div>
      <div>
        <label for="msg-correlation" class="block text-xs font-medium mb-1">{{ t('presetCorrelationKey') }}</label>
        <input
          id="msg-correlation"
          v-model="correlationKey"
          class="w-full px-2 py-1.5 border border-input rounded text-sm font-mono"
        />
      </div>
    </div>
    <PresetPicker
      v-if="messageName.trim()"
      ref="pickerRef"
      :key="messageName.trim()"
      :process-key="processKey"
      target-kind="MESSAGE"
      :target-ref="messageName.trim()"
      @change="onPickerChange"
    />
    <p v-else class="text-xs text-muted-foreground">{{ t('presetEnterMessageNameFirst') }}</p>
    <div class="flex items-center justify-end gap-2">
      <span v-if="result" class="text-xs font-mono text-muted-foreground truncate max-w-md">{{ result }}</span>
      <button
        type="button"
        class="px-4 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
        :disabled="!messageName.trim() || !pickerRef || askMissing.length > 0 || publishing"
        :title="askMissing.length ? t('presetFillAskFields', { fields: askMissing.join(', ') }) : ''"
        @click="publish"
      >
        {{ publishing ? t('loading') : t('presetPublish') }}
      </button>
    </div>
  </div>
</template>
