<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBreadcrumbStore } from '@/stores/breadcrumb'
import { ChevronRight } from 'lucide-vue-next'

const route = useRoute()
const { t } = useI18n()

// WO-ACL-11 criteria 3-5: the leaf label comes from the breadcrumb store, NOT
// from inject(). BreadcrumbNav sits ABOVE <router-view> in MainLayout, so an
// inject() from the page below could never reach it (P-54). The detail pages
// fill the store through useBreadcrumbLabel() (WO-ACL-15 criteria 17-19) when
// their data arrives; this crumb updates with it. A null label falls back to
// the route's static titleKey — the pre-data state never shows an empty string.
const breadcrumb = useBreadcrumbStore()

// WO-ACL-10 criterion 18: route meta carries locale KEYS (titleKey/parentTitleKey)
// instead of raw English literals; labels are resolved through t().
const breadcrumbs = computed(() => {
  const items: { label: string; to?: string | object }[] = []
  const meta = route.meta as { titleKey?: string; parentTitleKey?: string; parentTo?: string | object }

  if (meta.parentTitleKey && meta.parentTo) {
    items.push({ label: t(meta.parentTitleKey), to: meta.parentTo })
  }
  if (meta.titleKey) {
    items.push({ label: breadcrumb.crumbLabel || t(meta.titleKey) })
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