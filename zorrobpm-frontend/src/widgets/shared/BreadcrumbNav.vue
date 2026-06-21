<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ChevronRight } from 'lucide-vue-next'

const route = useRoute()

const breadcrumbs = computed(() => {
  const items: { label: string; to?: string }[] = []
  const meta = route.meta as { title?: string }
  const parentMeta = route.matched.length > 1
    ? (route.matched[route.matched.length - 2]?.meta as { title?: string })
    : undefined

  if (parentMeta?.title) {
    items.push({ label: parentMeta.title })
  }
  if (meta?.title) {
    items.push({ label: meta.title })
  }
  return items
})
</script>

<template>
  <nav
    v-if="breadcrumbs.length > 0"
    class="h-10 border-b border-border flex items-center px-6 bg-card"
  >
    <ol class="flex items-center gap-1.5 text-sm">
      <li
        v-for="(crumb, index) in breadcrumbs"
        :key="index"
        class="flex items-center gap-1.5"
      >
        <ChevronRight
          v-if="index > 0"
          class="h-3.5 w-3.5 text-muted-foreground"
        />
        <span :class="index === breadcrumbs.length - 1 ? 'text-foreground font-medium' : 'text-muted-foreground'">
          {{ crumb.label }}
        </span>
      </li>
    </ol>
  </nav>
</template>
