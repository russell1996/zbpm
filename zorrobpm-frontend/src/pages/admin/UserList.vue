<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { User, UserRole } from '@/entities/user/User'
import { getUsers, createUser, updateUser } from '@/services/userService'
import { useToast } from '@/composables/useToast'
import { useI18n } from 'vue-i18n'
import UserDetailPanel from './UserDetailPanel.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const toast = useToast()
const { t } = useI18n()

const users = ref<User[]>([])
const totalCount = ref(0)
const loading = ref(false)
const search = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingUser = ref<User | null>(null)
const expandedUserId = ref<string | null>(null)
// WO-INT-4 criterion 2: one list, filterable by account type (all / people / systems)
const typeFilter = ref<'ALL' | 'HUMAN' | 'SYSTEM'>('ALL')

const visibleUsers = computed(() =>
  typeFilter.value === 'ALL' ? users.value : users.value.filter((u) => u.userType === typeFilter.value),
)

const formUsername = ref('')
const formPassword = ref('')
const formFullName = ref('')
const formEmail = ref('')
const formRole = ref<UserRole>('USER')
const formActive = ref(true)
/** WO-ACL-18: default creation path is an invitation (one-time link), not a direct password. */
const formCreationMode = ref<string>('INVITE')

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
  formCreationMode.value = 'INVITE'
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
  if (!formUsername.value) {
    toast.warning(t('fillRequired'))
    return
  }
  // WO-ACL-19 (P2): a HUMAN account MUST have an email (every reset path is email-driven).
  // All accounts created through this form are HUMAN.
  if (!editingUser.value && !formEmail.value) {
    toast.warning(t('emailRequired'))
    return
  }
  if (editingUser.value && editingUser.value.userType === 'HUMAN' && !formEmail.value) {
    toast.warning(t('emailRequired'))
    return
  }
  if (!editingUser.value && formCreationMode.value === 'PASSWORD' && !formPassword.value) {
    toast.warning(t('passwordRequired'))
    return
  }
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
        password: formCreationMode.value === 'PASSWORD' ? formPassword.value : '',
        fullName: formFullName.value || null,
        email: formEmail.value || null,
        role: formRole.value,
        active: formActive.value,
        creationMode: formCreationMode.value,
      })
    }
    showForm.value = false
    await loadUsers()
    toast.success(t('saved'))
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    toast.error(msg || t('failedToSaveUser'))
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
      <h1 class="text-2xl font-bold">{{ t('users') }}</h1>
      <button
        class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
        @click="openCreate"
      >
        {{ t('addUser') }}
      </button>
    </div>

    <div class="flex items-center gap-4">
      <input
        v-model="search"
        type="text"
        :placeholder="t('searchByUsername')"
        class="px-3 py-2 border border-input rounded-md text-sm w-64 focus:outline-none focus:ring-2 focus:ring-ring"
        @input="loadUsers"
      />
      <!-- WO-INT-4 criterion 2: one list with a type filter — systems are not a separate screen -->
      <select
        v-model="typeFilter"
        class="px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
        data-testid="user-type-filter"
      >
        <option value="ALL">{{ t('filterAllUsers') }}</option>
        <option value="HUMAN">{{ t('filterHumanUsers') }}</option>
        <option value="SYSTEM">{{ t('filterSystemUsers') }}</option>
      </select>
      <span class="text-sm text-muted-foreground">{{ totalCount }} {{ t('usersCount') }}</span>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">{{ t('username') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('fullName') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('email') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('role') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="user in visibleUsers" :key="user.id">
          <tr
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            tabindex="0"
            @click="expandedUserId = expandedUserId === user.id ? null : user.id"
            @keydown.enter="expandedUserId = expandedUserId === user.id ? null : user.id"
          >
            <td class="px-4 py-3 font-mono">
              {{ user.username }}
              <!-- WO-INT-4 criterion 2: a system account is an integration, not a person -->
              <span
                v-if="user.userType === 'SYSTEM'"
                class="ml-2 inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wide bg-violet-100 text-violet-700"
              >
                {{ t('systemAccount') }}
              </span>
              <!-- WO-ACL-18 criterion 5: an outstanding invite token means the account is not yet active-with-password -->
              <span
                v-if="user.pendingInvitation"
                class="ml-2 inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wide bg-amber-100 text-amber-700"
                data-testid="pending-invitation-badge"
              >
                {{ t('invited') }}
              </span>
            </td>
            <td class="px-4 py-3">{{ user.fullName || '—' }}</td>
            <td class="px-4 py-3">{{ user.email || '—' }}</td>
            <td class="px-4 py-3">
              <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted">{{ user.role }}</span>
            </td>
            <td class="px-4 py-3">
              <StatusBadge :status="user.active ? 'ACTIVE' : 'INACTIVE'" />
            </td>
            <td class="px-4 py-3">
              <div class="flex items-center gap-2">
                <button class="text-sm text-primary hover:underline" @click.stop="openEdit(user)">{{ t('edit') }}</button>
                <button
                  class="text-sm hover:underline"
                  :class="user.active ? 'text-red-600' : 'text-green-600'"
                  @click.stop="toggleActive(user)"
                >
                  {{ user.active ? t('deactivate') : t('activate') }}
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
            <td colspan="6" class="px-4 py-8 text-center text-muted-foreground">{{ t('noUsers') }}</td>
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
        <h2 class="text-lg font-bold">{{ editingUser ? t('editUser') : t('addUser') }}</h2>
        <div class="space-y-3">
          <div>
            <label class="block text-sm font-medium mb-1">{{ t('username') }}</label>
            <input
              v-model="formUsername"
              type="text"
              :disabled="!!editingUser"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
            />
          </div>
          <!-- WO-ACL-18: choose how the account is created (invitation link vs direct password) -->
          <div v-if="!editingUser">
            <label class="block text-sm font-medium mb-1">{{ t('creationModeLabel') }}</label>
            <select v-model="formCreationMode" class="w-full px-3 py-2 border border-input rounded-md text-sm">
              <option value="INVITE">{{ t('invitationMode') }}</option>
              <option value="PASSWORD">{{ t('passwordMode') }}</option>
            </select>
            <p v-if="formCreationMode === 'INVITE'" class="mt-1 text-xs text-muted-foreground">
              {{ t('inviteHint') }}
            </p>
          </div>
          <div v-if="editingUser || formCreationMode === 'PASSWORD'">
            <label class="block text-sm font-medium mb-1">{{ editingUser ? t('newPasswordOptional') : t('password') }}</label>
            <input
              v-model="formPassword"
              type="password"
              autocomplete="new-password"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div v-else-if="formCreationMode === 'INVITE'" class="text-xs text-muted-foreground">
            {{ t('inviteEmailNote') }}
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">{{ t('fullName') }}</label>
            <input
              v-model="formFullName"
              type="text"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">{{ t('email') }}</label>
            <input
              v-model="formEmail"
              type="email"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">{{ t('role') }}</label>
            <select v-model="formRole" class="w-full px-3 py-2 border border-input rounded-md text-sm">
              <option value="USER">{{ t('userRole') }}</option>
              <option value="ADMIN">{{ t('adminRole') }}</option>
              <option value="SUPER_ADMIN">{{ t('superAdminRole') }}</option>
            </select>
          </div>
          <div class="flex items-center gap-2">
            <input id="active" v-model="formActive" type="checkbox" class="rounded" />
            <label for="active" class="text-sm">{{ t('active') }}</label>
          </div>
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button
            class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted transition-colors"
            @click="showForm = false"
          >
            {{ t('cancel') }}
          </button>
          <button
            :disabled="saving"
            class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity disabled:opacity-50"
            @click="save"
          >
            {{ editingUser ? t('save') : t('add') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
