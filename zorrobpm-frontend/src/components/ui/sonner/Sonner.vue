<script lang="ts" setup>
import type { ToasterProps } from "vue-sonner"
import { reactiveOmit } from "@vueuse/core"
import { CircleCheckIcon, InfoIcon, Loader2Icon, OctagonXIcon, TriangleAlertIcon, XIcon } from "lucide-vue-next"
import { Toaster as Sonner } from "vue-sonner"

// WO-UI-8: vue-sonner's own stylesheet provides the layout base (position:fixed viewport,
// enter/exit animations); the design-system look comes from the data-variant classes below.
import "vue-sonner/style.css"

const props = defineProps<ToasterProps>()
const delegatedProps = reactiveOmit(props, "toastOptions")
</script>

<template>
  <Sonner
    class="toaster group"
    :toast-options="{
      classes: {
        // Tailwind v4: group-[.selector]: variants are not generated — v4 idiom is group-data-*:
        // viewport ol carries data-sonner-toaster, each toast li carries data-sonner-toast
        // WO-UI-8 step 0b: toast blended into white page (bg-background on bg-background) —
        // make it pop: stronger shadow + hairline ring over the border
        // variant A: pure elevation — keep white, lift with shadow-2xl + hairline ring
        toast: 'group toast group-data-sonner-toaster:bg-background group-data-sonner-toaster:text-foreground group-data-sonner-toaster:border group-data-sonner-toaster:border-border group-data-sonner-toaster:rounded-lg group-data-sonner-toaster:shadow-2xl group-data-sonner-toaster:ring-1 group-data-sonner-toaster:ring-black/5',
        description: 'group-data-sonner-toast:text-muted-foreground',
        actionButton:
          'group-data-sonner-toast:bg-primary group-data-sonner-toast:text-primary-foreground',
        cancelButton:
          'group-data-sonner-toast:bg-muted group-data-sonner-toast:text-muted-foreground',
      },
    }"
    v-bind="delegatedProps"
  >
    <template #success-icon>
      <CircleCheckIcon class="size-4 text-primary" />
    </template>
    <template #info-icon>
      <InfoIcon class="size-4" />
    </template>
    <template #warning-icon>
      <TriangleAlertIcon class="size-4" />
    </template>
    <template #error-icon>
      <OctagonXIcon class="size-4 text-destructive" />
    </template>
    <template #loading-icon>
      <div>
        <Loader2Icon class="size-4 animate-spin" />
      </div>
    </template>
    <template #close-icon>
      <XIcon class="size-4" />
    </template>
  </Sonner>
</template>
