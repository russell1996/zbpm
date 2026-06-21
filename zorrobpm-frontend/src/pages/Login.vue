<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuth } from '@/features/auth/useAuth'

const { login, isLoading } = useAuth()

onMounted(() => {
  login()
})
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-muted">
    <div class="text-center">
      <div class="flex justify-center mb-4">
        <div class="h-12 w-12 rounded-lg bg-primary flex items-center justify-center">
          <span class="text-sm font-black text-primary-foreground">BPM</span>
        </div>
      </div>
      <h1 class="text-2xl font-bold mb-4">ZBPM</h1>
      <p class="text-muted-foreground">
        {{ isLoading ? 'Redirecting to login...' : 'Redirecting to login...' }}
      </p>
    </div>
  </div>
</template>
