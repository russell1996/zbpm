<script setup lang="ts">
import { useAuth } from '@/features/auth/useAuth'

const { login } = useAuth()
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-muted">
    <div class="text-center max-w-md">
      <h1 class="text-2xl font-bold mb-4">Access Denied</h1>
      <p class="text-muted-foreground mb-6">
        You do not have access to ZBPM. Please contact your administrator
        to be added to the user white list.
      </p>
      <button
        class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity"
        @click="login()"
      >
        Try Again
      </button>
    </div>
  </div>
</template>
