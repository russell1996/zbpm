<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { User, UserRole } from '@/entities/user/User'
import { getUsers, createUser, updateUser } from '@/services/userService'
import { useToast } from '@/composables/useToast'
import UserDetailPanel from './UserDetailPanel.vue'

const toast = useToast()

const users = ref<User[]>([])
const totalCount = ref(0)
const loading = ref(false)
const search = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingUser = ref<User | null>(null)
const expandedUserId = ref<string | null>(null)

const formUsername = ref('')
const formPassword = ref('')
const formFullName = ref('')
const formEmail = ref('')
const formRole = ref<UserRole>('USER')
const formActive = ref(true)

async function loadUsers() {
  loading.value = true
  try {
    const result = await getUsers({ username: search.value || undefined })
    users.value = result.data
    totalCount.value = result.totalElements
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingUser.value = null
  formUsername.value = ''
  formPassword.value = ''
  formFullName.value = ''
  formEmail.value = ''
  formRole.value = 'USER'
  formActive.value = true
  showForm.value = true
}

function openEdit(user: User) {
  editingUser.value = user
  formUsername.value = user.username
  formPassword.value = ''
  formFullName.value = user.fullName || ''
  formEmail.value = user.email || ''
  formRole.value = user.role
  formActive.value = user.active
  showForm.value = true
}

async function save() {
  saving.value = true
  try {
    if (editingUser.value) {
      await updateUser(editingUser.value.id, {
        fullName: formFullName.value || null,
        email: formEmail.value || null,
        role: formRole.value,
        active: formActive.value,
        password: formPassword.value || undefined,
      })
    } else {
      await createUser({
        username: formUsername.value,
        password: formPassword.value,
        fullName: formFullName.value || null,
        email: formEmail.value || null,
        role: formRole.value,
        active: formActive.value,
      })
    }
    showForm.value = false
    await loadUsers()
    toast.success('Saved')
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    toast.error(msg || 'Failed to save user')
  } finally {
    saving.value = false
  }
}

async function toggleActive(user: User) {
  await updateUser(user.id, {
    fullName: user.fullName,
    email: user.email,
    role: user.role,
    active: !user.active,
  })
  await loadUsers()
}

onMounted(loadUsers)
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">Users</h1>
      <button
        class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
        @click="openCreate"
      >
        Create User
      </button>
    </div>

    <div class="flex items-center gap-4">
      <input
        v-model="search"
        type="text"
        placeholder="Search by username..."
        class="px-3 py-2 border border-input rounded-md text-sm w-64 focus:outline-none focus:ring-2 focus:ring-ring"
        @input="loadUsers"
      />
      <span class="text-sm text-muted-foreground">{{ totalCount }} users</span>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">Username</th>
            <th class="px-4 py-3 text-left font-medium">Full Name</th>
            <th class="px-4 py-3 text-left font-medium">Email</th>
            <th class="px-4 py-3 text-left font-medium">Role</th>
            <th class="px-4 py-3 text-left font-medium">Status</th>
            <th class="px-4 py-3 text-left font-medium">Actions</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="user in users" :key="user.id">
          <tr class="border-t border-border hover:bg-muted/50">
            <td class="px-4 py-3 font-mono">{{ user.username }}</td>
            <td class="px-4 py-3">{{ user.fullName || '—' }}</td>
            <td class="px-4 py-3">{{ user.email || '—' }}</td>
            <td class="px-4 py-3">
              <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted">{{ user.role }}</span>
            </td>
            <td class="px-4 py-3">
              <span
                class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                :class="user.active ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'"
              >
                {{ user.active ? 'Active' : 'Inactive' }}
              </span>
            </td>
            <td class="px-4 py-3">
              <div class="flex items-center gap-2">
                <button class="text-sm text-primary hover:underline" @click="expandedUserId = expandedUserId === user.id ? null : user.id">
                  {{ expandedUserId === user.id ? 'Collapse' : 'Details' }}
                </button>
                <button class="text-sm text-primary hover:underline" @click="openEdit(user)">Edit</button>
                <button
                  class="text-sm hover:underline"
                  :class="user.active ? 'text-red-600' : 'text-green-600'"
                  @click="toggleActive(user)"
                >
                  {{ user.active ? 'Deactivate' : 'Activate' }}
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="expandedUserId === user.id">
            <td colspan="6" class="p-0">
              <UserDetailPanel :user="user" @close="expandedUserId = null" />
            </td>
          </tr>
          </template>
          <tr v-if="users.length === 0">
            <td colspan="6" class="px-4 py-8 text-center text-muted-foreground">No users found</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-if="showForm"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showForm = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h2 class="text-lg font-bold">{{ editingUser ? 'Edit User' : 'Create User' }}</h2>
        <div class="space-y-3">
          <div>
            <label class="block text-sm font-medium mb-1">Username</label>
            <input
              v-model="formUsername"
              type="text"
              :disabled="!!editingUser"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">{{ editingUser ? 'New password (optional)' : 'Password' }}</label>
            <input
              v-model="formPassword"
              type="password"
              autocomplete="new-password"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">Full Name</label>
            <input
              v-model="formFullName"
              type="text"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">Email</label>
            <input
              v-model="formEmail"
              type="email"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">Role</label>
            <select v-model="formRole" class="w-full px-3 py-2 border border-input rounded-md text-sm">
              <option value="USER">USER</option>
              <option value="ADMIN">ADMIN</option>
            </select>
          </div>
          <div class="flex items-center gap-2">
            <input id="active" v-model="formActive" type="checkbox" class="rounded" />
            <label for="active" class="text-sm">Active</label>
          </div>
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button
            class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted transition-colors"
            @click="showForm = false"
          >
            Cancel
          </button>
          <button
            :disabled="saving"
            class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity disabled:opacity-50"
            @click="save"
          >
            {{ editingUser ? 'Save' : 'Create' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
