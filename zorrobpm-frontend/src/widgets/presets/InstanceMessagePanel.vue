<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import PresetPicker from './PresetPicker.vue'
import { publishMessage } from '@/services/messagePublishService'
import { errorMessage } from '@/shared/lib/utils'
import { useToast } from '@/composables/useToast'

/**
 * WO-VT-3 (фронт, VT-4): публикация сообщения из шаблона MESSAGE.
 * Имя сообщения и correlationKey вводит пользователь (ref шаблона = имя
 * сообщения); переменные — PresetPicker (ручной ввод или шаблон).
 *
 * WO-VT-3 (A-NEW-2): пикер НЕ пересоздаётся при доборе имени — :key убран,
 * targetRef обновляется живой пропсой; введённые переменные сохраняются
 * внутри PresetPicker.manualVars/templateVars и выживают при смене имени.
 */
const props = defineProps<{
  processKey: string
  processInstanceId?: string | null
  /**
   * WO-VT-3 раунд 2 (E-VT3-1, мокап А): голый режим для модалки «Отправить
   * сообщение» — без карточки и заголовка (их даёт диалог), только форма.
   */
  bare?: boolean
}>()

const { t } = useI18n()
const toast = useToast()

const messageName = ref('')
const correlationKey = ref('')
const pickerRef = ref<InstanceType<typeof PresetPicker> | null>(null)
const askMissing = ref<string[]>([])
// WO-VT-1 раунд 2 (Б-3): guard по невалидным строкам — как на 5 соседних
// страницах (publish/disabled раньше его не учитывали, невалидный JSON/LONG
// уходил в /messages/publish, который пресет-валидацию не выполняет).
const pickerInvalid = ref(false)
const publishing = ref(false)
const result = ref<string | null>(null)

function onPickerChange() {
  askMissing.value = pickerRef.value?.missingAsk ?? []
  pickerInvalid.value = pickerRef.value?.hasErrors ?? false
}

async function publish() {
  if (!messageName.value.trim() || !pickerRef.value || askMissing.value.length || pickerInvalid.value || publishing.value) return
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
  <div :class="bare ? 'space-y-3' : 'border border-border rounded-lg p-4 bg-card space-y-3'" :data-testid="bare ? 'message-panel-bare' : 'message-panel-card'">
    <h3 v-if="!bare" class="text-sm font-bold">{{ t('presetPublishMessage') }}</h3>
    <!-- WO-VT-3 Дополнение №2 п.2: строка «зачем это» (до решения по IA). -->
    <p class="text-xs text-muted-foreground">{{ t('presetWhyMessage') }}</p>
    <div class="grid grid-cols-1 min-[560px]:grid-cols-2 gap-3">
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
    <!-- WO-VT-3 (A-NEW-2): без :key — key пересоздавал пикер при каждой
         букве имени и сносил введённые переменные. targetRef вычисляется
         один раз при появлении имени; смена имени потом пикер не трогает
         (сообщение то же, меняется только текст). -->
    <PresetPicker
      v-if="messageName.trim()"
      ref="pickerRef"
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
        :disabled="!messageName.trim() || !pickerRef || askMissing.length > 0 || pickerInvalid || publishing"
        :title="askMissing.length ? t('presetFillAskFields', { fields: askMissing.join(', ') }) : ''"
        @click="publish"
      >
        {{ publishing ? t('loading') : t('presetPublish') }}
      </button>
    </div>
  </div>
</template>
