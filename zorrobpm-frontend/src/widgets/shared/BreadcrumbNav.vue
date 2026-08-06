<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ChevronRight } from 'lucide-vue-next'

const route = useRoute()

const breadcrumbs = computed(() => {
  const items: { label: string; to?: string | object }[] = []
  const meta = route.meta as { title?: string; parentTitle?: string; parentTo?: string | object }

  // Parent item (clickable link back to list)
  if (meta.parentTitle && meta.parentTo) {
    items.push({ label: meta.parentTitle, to: meta.parentTo })
  }
  // Current page
  if (meta?.title) {
    items.push({ label: meta.title })
  }
  return items
})
</script>

<template>
  <nav
    v-if="breadcrumbs.length > 1"
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
        <router-link
          v-if="crumb.to"
          :to="crumb.to"
          class="text-muted-foreground hover:text-foreground transition-colors"
        >
          {{ crumb.label }}
        </router-link>
        <span v-else class="text-foreground font-medium">
          {{ crumb.label }}
        </span>
      </li>
    </ol>
  </nav>
</template>
