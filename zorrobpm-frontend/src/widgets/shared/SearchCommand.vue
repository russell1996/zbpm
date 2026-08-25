<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Search, X } from 'lucide-vue-next'
import { getProcessDefinitions } from '@/services/processService'
import { getProcessInstances } from '@/services/instanceService'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const router = useRouter()
const isOpen = ref(false)
const query = ref('')
const inputRef = ref<HTMLInputElement>()
const results = ref<{ type: string; label: string; sub: string; to: string }[]>([])
const loading = ref(false)

function open() {
  isOpen.value = true
  query.value = ''
  results.value = []
  nextTick(() => inputRef.value?.focus())
}

function close() {
  isOpen.value = false
  query.value = ''
  results.value = []
}

async function search() {
  if (query.value.length < 2) {
    results.value = []
    return
  }
  loading.value = true
  try {
    const q = query.value.toLowerCase()
    const [defs, instances] = await Promise.all([
      getProcessDefinitions({ name: q, pageSize: 5 }),
      getProcessInstances({ pageSize: 20 }),
    ])
    results.value = []
    for (const d of defs.data) {
      results.value.push({
        type: 'Definition',
        label: d.name || d.key,
        sub: `v${d.version} · ${d.key}`,
        to: `/processes/definitions/${d.id}`,
      })
    }
    for (const i of instances.data) {
      if (i.id.toLowerCase().includes(q)) {
        results.value.push({
          type: 'Instance',
          label: `Instance ${i.id.slice(0, 8)}...`,
          sub: i.completedAt ? 'Completed' : 'Running',
          to: `/processes/instances/${i.id}`,
        })
      }
    }
  } finally {
    loading.value = false
  }
}

function navigate(to: string) {
  close()
  router.push(to)
}

function onKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
    e.preventDefault()
    if (isOpen.value) close()
    else open()
  }
  if (e.key === 'Escape' && isOpen.value) {
    close()
  }
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <button
    class="flex items-center gap-2 px-3 py-1.5 text-sm text-muted-foreground hover:text-foreground border border-border rounded-md transition-colors min-w-[240px]"
    @click="open"
  >
    <Search class="h-4 w-4" />
    <span>{{ t('searchPlaceholder') }}</span>
  </button>

  <Teleport to="body">
    <div
      v-if="isOpen"
      class="fixed inset-0 z-50 flex items-start justify-center pt-[20vh]"
      @click.self="close"
    >
      <div class="fixed inset-0 bg-black/50" @click="close" />
      <div class="relative bg-card rounded-lg shadow-2xl border border-border w-full max-w-lg z-50">
        <div class="flex items-center gap-3 px-4 py-3 border-b border-border">
          <Search class="h-4 w-4 text-muted-foreground" />
          <input
            ref="inputRef"
            v-model="query"
            type="text"
            :placeholder="t('searchProcessesInstances')"
            class="flex-1 text-sm bg-transparent focus:outline-none"
            @input="search"
          />
          <button class="p-1 hover:bg-muted rounded" @click="close">
            <X class="h-4 w-4" />
          </button>
        </div>
        <div class="max-h-80 overflow-y-auto p-2">
          <div v-if="loading" class="px-4 py-3 text-sm text-muted-foreground">{{ t('searching') }}</div>
          <div v-else-if="results.length === 0 && query.length >= 2" class="px-4 py-3 text-sm text-muted-foreground">
            {{ t('noResultsFound') }}
          </div>
          <button
            v-for="(r, i) in results"
            :key="i"
            class="w-full flex items-center justify-between px-4 py-2.5 text-sm rounded-md hover:bg-muted transition-colors text-left"
            @click="navigate(r.to)"
          >
            <div>
              <span class="font-medium">{{ r.label }}</span>
              <span class="text-muted-foreground ml-2">{{ r.sub }}</span>
            </div>
            <span class="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded">{{ r.type }}</span>
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
