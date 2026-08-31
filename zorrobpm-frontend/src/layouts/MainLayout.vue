<script setup lang="ts">
import { ref } from 'vue'
import { useIsMobile } from '@/shared/lib/responsive'
import { SidebarProvider } from '@/components/ui/sidebar'
import SidebarNavShadcn from '@/widgets/shared/SidebarNavShadcn.vue'
import HeaderBar from '@/widgets/shared/HeaderBar.vue'

const isMobile = useIsMobile()
const sidebarOpen = ref(!isMobile.value)
</script>

<template>
  <SidebarProvider class="h-screen">
    <div
      v-if="isMobile && sidebarOpen"
      class="fixed inset-0 bg-black/50 z-40"
      @click="sidebarOpen = false"
    />
    <div
      class="z-50 transition-transform duration-200 h-full shrink-0"
      :class="[
        isMobile ? 'fixed inset-y-0 left-0' : 'relative',
        isMobile && !sidebarOpen ? '-translate-x-full' : 'translate-x-0',
      ]"
    >
      <SidebarNavShadcn
        @navigate="sidebarOpen = false"
      />
    </div>
    <div class="flex-1 flex flex-col overflow-hidden min-w-0">
      <HeaderBar @toggle-sidebar="sidebarOpen = !sidebarOpen" :show-menu-button="isMobile" />
      <main class="flex-1 overflow-auto p-2 md:p-3">
        <router-view />
      </main>
    </div>
  </SidebarProvider>
</template>
