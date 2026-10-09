<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useIsMobile } from '@/shared/lib/responsive'
import { SidebarProvider } from '@/components/ui/sidebar'
import SidebarNavShadcn from '@/widgets/shared/SidebarNavShadcn.vue'
import HeaderBar from '@/widgets/shared/HeaderBar.vue'
import { useRealtimeEvents } from '@/composables/useRealtimeEvents'
import { useRealtimeChannel } from '@/composables/useRealtimeChannel'

const isMobile = useIsMobile()
const sidebarOpen = ref(!isMobile.value)

// WO-UI-18 часть A: единая точка realtime-подписки. MainLayout монтируется
// один раз для всех страниц, требующих auth (router.beforeEach), и переживает
// навигацию между ними — соединение не пересоздаётся на каждый переход.
const realtime = useRealtimeEvents()
// WO-UI-26 Доп.6/Доп.7: индикатор в шапке (ChannelStatusDot) читает состояние
// ЭТОГО экземпляра через useRealtimeChannel (модульный синглтон refs —
// provide/inject от setup к setup НЕ работает: inject в <script setup>
// потомка резолвится раньше provide родителя при том же тике монтирования).
useRealtimeChannel().set(realtime)
onMounted(() => realtime.connect())
onUnmounted(() => realtime.disconnect())
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
      <!-- WO-UI-26 Доп.2/Доп.6/Доп.7: НИКАКИХ полос над контентом — оба старых
           баннера (sessionExpired/realtimeDown) сдвигали весь <main> на высоту
           плашки при каждом флаппинге канала (layout shift). Состояние канала —
           только нейтральная точка в шапке (ChannelStatusDot, гистерезис 5 с),
           детали — по клику. Контент не двигается никогда. -->
      <main class="flex-1 overflow-auto p-2 md:p-3">
        <router-view />
      </main>
    </div>
  </SidebarProvider>
</template>
