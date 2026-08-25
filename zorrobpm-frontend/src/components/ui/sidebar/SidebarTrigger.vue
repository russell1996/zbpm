<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { PanelLeft } from "lucide-vue-next"
import { useI18n } from "vue-i18n"
import { cn } from '@/shared/lib/utils'
import { Button } from '@/components/ui/button'
import { useSidebar } from "./utils"

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()

const { toggleSidebar } = useSidebar()
const { t } = useI18n()
</script>

<template>
  <Button
    data-sidebar="trigger"
    variant="ghost"
    size="icon"
    :class="cn('h-7 w-7', props.class)"
    :aria-label="t('collapseSidebar')"
    @click="toggleSidebar"
  >
    <PanelLeft />
    <span class="sr-only">{{ t('collapseSidebar') }}</span>
  </Button>
</template>
