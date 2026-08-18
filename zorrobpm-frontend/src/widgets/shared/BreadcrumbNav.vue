<script setup lang="ts">
import { computed, inject, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ChevronRight } from 'lucide-vue-next'

const route = useRoute()
const { t } = useI18n()

// WO-ACL-8 criterion 12: the process detail page provides its name so the
// breadcrumb shows the actual process name instead of the static title.
// WO-ACL-10 criterion 19: the provided value is a REACTIVE ref — the detail page
// provides it synchronously in setup() and fills it when data arrives, so the
// last crumb updates when the process changes.
const processName = inject<Ref<string | null> | undefined>('processName')

// WO-ACL-10 criterion 18: route meta carries locale KEYS (titleKey/parentTitleKey)
// instead of raw English literals; labels are resolved through t().
const breadcrumbs = computed(() => {
  const items: { label: string; to?: string | object }[] = []
  const meta = route.meta as { titleKey?: string; parentTitleKey?: string; parentTo?: string | object }

  if (meta.parentTitleKey && meta.parentTo) {
    items.push({ label: t(meta.parentTitleKey), to: meta.parentTo })
  }
  if (meta.titleKey) {
    items.push({ label: processName?.value || t(meta.titleKey) })
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