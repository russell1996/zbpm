<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from '@/shared/lib/utils'

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <div
    data-sidebar="group"
    :class="cn('relative flex w-full min-w-0 flex-col p-2 group-data-[collapsible=icon]:p-0 group-data-[collapsible=icon]:py-0.5', props.class)"
  >
    <slot />
  </div>
</template>
