<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from '@/shared/lib/utils'

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <ul
    data-sidebar="menu"
    :class="cn('flex w-full min-w-0 flex-col gap-1 group-data-[collapsible=icon]:items-center', props.class)"
  >
    <slot />
  </ul>
</template>
