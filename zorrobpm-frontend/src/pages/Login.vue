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
      <h1 class="text-2xl font-bold mb-4">ZorroBPM</h1>
      <p class="text-muted-foreground">
        {{ isLoading ? 'Redirecting to login...' : 'Redirecting to login...' }}
      </p>
    </div>
  </div>
</template>
