<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { SchemaField } from '@/services/formService'

const props = defineProps<{ modelValue: SchemaField[] }>()
const emit = defineEmits<{ 'update:modelValue': [fields: SchemaField[]] }>()

const { t } = useI18n()

const fields = ref<SchemaField[]>(props.modelValue.map(f => ({ ...f })))

watch(() => props.modelValue, (v) => {
  fields.value = v.map(f => ({ ...f }))
}, { deep: true })

const fieldTypes = ['string', 'number', 'integer', 'boolean', 'date', 'datetime', 'array']

function addField() {
  fields.value.push({ key: '', label: '', type: 'string' })
  emitUpdate()
}

function removeField(index: number) {
  fields.value.splice(index, 1)
  emitUpdate()
}

function emitUpdate() {
  emit('update:modelValue', fields.value.map(f => ({ ...f })))
}

function onFieldChange() {
  emitUpdate()
}

function getEnumStr(field: SchemaField): string {
  return field.enum ? field.enum.join(', ') : ''
}

function setEnumStr(field: SchemaField, val: string) {
  field.enum = val.split(',').map(s => s.trim()).filter(s => s.length > 0)
  if (field.enum.length === 0) field.enum = undefined
  onFieldChange()
}
</script>

<template>
  <div class="space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-medium">{{ t('schemaFields') }}</h3>
      <button
        type="button"
        class="text-xs text-primary hover:underline"
        @click="addField"
      >{{ t('addField') }}</button>
    </div>

    <div v-if="fields.length === 0" class="text-xs text-muted-foreground py-2">
      {{ t('noFields') }}
    </div>

    <div v-for="(field, idx) in fields" :key="idx" class="border border-border rounded-md p-3 space-y-2">
      <div class="flex items-center gap-2">
        <input
          v-model="field.key"
          :placeholder="t('fieldKey')"
          class="flex-1 px-2 py-1 border border-border rounded text-sm bg-background"
          @change="onFieldChange"
        />
        <input
          v-model="field.label"
          :placeholder="t('fieldLabel')"
          class="flex-1 px-2 py-1 border border-border rounded text-sm bg-background"
          @change="onFieldChange"
        />
        <select
          v-model="field.type"
          class="px-2 py-1 border border-border rounded text-sm bg-background"
          @change="onFieldChange"
        >
          <option v-for="ft in fieldTypes" :key="ft" :value="ft">{{ ft }}</option>
        </select>
        <label class="flex items-center gap-1 text-xs">
          <input
            type="checkbox"
            :checked="field.required"
            @change="field.required = ($event.target as HTMLInputElement).checked; onFieldChange()"
          />
          {{ t('required') }}
        </label>
        <button
          type="button"
          class="text-xs text-destructive hover:underline"
          @click="removeField(idx)"
        >{{ t('remove') }}</button>
      </div>

      <!-- Constraints row -->
      <div class="flex items-center gap-2 text-xs">
        <input
          v-if="field.type === 'string'"
          :value="field.maxLength"
          type="number"
          :placeholder="t('maxLength')"
          class="w-20 px-2 py-1 border border-border rounded bg-background"
          @change="field.maxLength = +($event.target as HTMLInputElement).value || undefined; onFieldChange()"
        />
        <input
          v-if="field.type === 'string'"
          v-model="field.pattern"
          :placeholder="t('pattern')"
          class="w-32 px-2 py-1 border border-border rounded bg-background"
          @change="onFieldChange"
        />
        <input
          v-if="field.type === 'number' || field.type === 'integer'"
          :value="field.min"
          type="number"
          :placeholder="t('min')"
          class="w-20 px-2 py-1 border border-border rounded bg-background"
          @change="field.min = +($event.target as HTMLInputElement).value || undefined; onFieldChange()"
        />
        <input
          v-if="field.type === 'number' || field.type === 'integer'"
          :value="field.max"
          type="number"
          :placeholder="t('max')"
          class="w-20 px-2 py-1 border border-border rounded bg-background"
          @change="field.max = +($event.target as HTMLInputElement).value || undefined; onFieldChange()"
        />
        <input
          v-if="field.type === 'array'"
          v-model="field.itemsType"
          :placeholder="t('itemsType')"
          class="w-20 px-2 py-1 border border-border rounded bg-background"
          @change="onFieldChange"
        />
        <input
          v-if="field.type === 'array'"
          :value="field.minItems"
          type="number"
          :placeholder="t('minItems')"
          class="w-20 px-2 py-1 border border-border rounded bg-background"
          @change="field.minItems = +($event.target as HTMLInputElement).value || undefined; onFieldChange()"
        />
        <input
          v-if="field.type === 'array'"
          :value="field.maxItems"
          type="number"
          :placeholder="t('maxItems')"
          class="w-20 px-2 py-1 border border-border rounded bg-background"
          @change="field.maxItems = +($event.target as HTMLInputElement).value || undefined; onFieldChange()"
        />
        <input
          :value="getEnumStr(field)"
          :placeholder="t('enumValues')"
          class="flex-1 px-2 py-1 border border-border rounded bg-background"
          @change="setEnumStr(field, ($event.target as HTMLInputElement).value)"
        />
      </div>
    </div>
  </div>
</template>
