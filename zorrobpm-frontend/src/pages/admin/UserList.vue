<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import type { User, UserRole, CreationMode } from '@/entities/user/User'
import { getUsers, createUser, updateUser } from '@/services/userService'
import { useToast } from '@/composables/useToast'
import { useI18n } from 'vue-i18n'
import UserDetailPanel from './UserDetailPanel.vue'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'
import { ChevronRight } from 'lucide-vue-next'

const toast = useToast()
const { t } = useI18n()

const users = ref<User[]>([])
const totalCount = ref(0)
const loading = ref(false)
const search = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingUser = ref<User | null>(null)
const selectedUser = ref<User | null>(null)
const editing = ref(false)
const editForm = reactive<{ fullName: string; email: string; role: UserRole; active: boolean; password: string }>({
  fullName: '', email: '', role: 'USER', active: true, password: '',
})
// WO-INT-4 criterion 2: one list, filterable by account type (all / people / systems)
const typeFilter = ref<'ALL' | 'HUMAN' | 'SYSTEM'>('ALL')
// WO-UI-16: server-side active filter (default: only active); ALL = no active param, INACTIVE = active:false
const activeFilter = ref<'ACTIVE' | 'ALL' | 'INACTIVE'>('ACTIVE')
const activeParam = computed(() => (activeFilter.value === 'ACTIVE' ? true : activeFilter.value === 'INACTIVE' ? false : undefined))

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
/** WO-UI-10 Phase 2: account type — HUMAN (default) or SYSTEM (integration, API-key only). */
const formUserType = ref<'HUMAN' | 'SYSTEM'>('HUMAN')

async function loadUsers() {
  loading.value = true
  try {
    const result = await getUsers({ username: search.value || undefined, active: activeParam.value })
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
  formUserType.value = 'HUMAN'
  showForm.value = true
}

function startEdit() {
  if (!selectedUser.value) return
  editForm.fullName = selectedUser.value.fullName || ''
  editForm.email = selectedUser.value.email || ''
  editForm.role = selectedUser.value.role
  editForm.active = selectedUser.value.active
  editForm.password = ''
  editing.value = true
}

async function saveEdit() {
  if (!selectedUser.value) return
  const requiresEmail = selectedUser.value.userType === 'HUMAN'
  if (requiresEmail && !editForm.email) {
    toast.warning(t('emailRequired'))
    return
  }
  saving.value = true
  try {
    const updated = await updateUser(selectedUser.value.id, {
      fullName: editForm.fullName || null,
      email: editForm.email || null,
      role: editForm.role,
      active: editForm.active,
      password: editForm.password || undefined,
    })
    // Reload the list so the Drawer shows the full, fresh user (the update response may
    // omit fields, which would otherwise render as "—" until a page refresh).
    await loadUsers()
    const refreshed = users.value.find((u) => u.id === updated.id)
    // WO-UI-17 F24: updateUser returns IdDTO (id only), not a full User — the
    // reloaded row is the display object; if the edited user fell out of the
    // reloaded list (e.g. search filter), keep the previous full object rather
    // than rendering an id-only stub as dashes.
    selectedUser.value = refreshed ?? selectedUser.value
    editing.value = false
    editForm.password = ''
    toast.success(t('saved'))
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    toast.error(err?.response?.data?.message || t('failedToSaveUser'))
  } finally {
    saving.value = false
  }
}

function cancelEdit() {
  editing.value = false
  editForm.password = ''
}

async function save() {
  if (!formUsername.value) {
    toast.warning(t('fillRequired'))
    return
  }
  const requiresEmail = formUserType.value === 'HUMAN'
  if (requiresEmail && !formEmail.value) {
    toast.warning(t('emailRequired'))
    return
  }
  if (formUserType.value !== 'SYSTEM' && formCreationMode.value === 'PASSWORD' && !formPassword.value) {
    toast.warning(t('passwordRequired'))
    return
  }
  saving.value = true
  try {
    await createUser({
      username: formUsername.value,
      password: formUserType.value === 'SYSTEM' ? '' : (formCreationMode.value === 'PASSWORD' ? formPassword.value : ''),
      fullName: formFullName.value || null,
      email: formEmail.value || null,
      role: formRole.value,
      active: formActive.value,
      creationMode: formUserType.value === 'SYSTEM' ? undefined : (formCreationMode.value as CreationMode),
      userType: formUserType.value,
    })
    showForm.value = false
    await loadUsers()
    toast.success(t('saved'))
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    const msg = err?.response?.data?.message
    toast.error(msg || t('failedToSaveUser'))
  } finally {
    saving.value = false
  }
}

watch(activeFilter, () => { loadUsers() })

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
      <Select v-model="typeFilter" class="w-48">
        <SelectTrigger data-testid="user-type-filter" class="w-48 px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="ALL" data-testid="user-type-filter-ALL">{{ t('filterAllUsers') }}</SelectItem>
          <SelectItem value="HUMAN" data-testid="user-type-filter-HUMAN">{{ t('filterHumanUsers') }}</SelectItem>
          <SelectItem value="SYSTEM" data-testid="user-type-filter-SYSTEM">{{ t('filterSystemUsers') }}</SelectItem>
        </SelectContent>
      </Select>
      <!-- WO-UI-16: active filter — server-side, default ACTIVE -->
      <Select v-model="activeFilter" class="w-48">
        <SelectTrigger data-testid="user-active-filter" class="w-48 px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="ACTIVE" data-testid="user-active-filter-ACTIVE">{{ t('filterActiveOnly') }}</SelectItem>
          <SelectItem value="ALL" data-testid="user-active-filter-ALL">{{ t('filterAllStatuses') }}</SelectItem>
          <SelectItem value="INACTIVE" data-testid="user-active-filter-INACTIVE">{{ t('filterInactiveOnly') }}</SelectItem>
        </SelectContent>
      </Select>
      <span class="text-sm text-muted-foreground">{{ totalCount }} {{ t('usersCount') }}</span>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <div v-else class="border border-border rounded-lg overflow-y-auto max-h-[60vh]">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium sticky top-0 bg-muted">{{ t('username') }}</th>
            <th class="px-4 py-3 text-left font-medium sticky top-0 bg-muted">{{ t('fullName') }}</th>
            <th class="px-4 py-3 text-left font-medium sticky top-0 bg-muted">{{ t('email') }}</th>
            <th class="px-4 py-3 text-left font-medium sticky top-0 bg-muted">{{ t('role') }}</th>
            <th class="px-4 py-3 text-left font-medium sticky top-0 bg-muted">{{ t('status') }}</th>
            <th class="px-4 py-3 w-8 sticky top-0 bg-muted"></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="user in visibleUsers" :key="user.id">
          <tr
            class="border-t border-border hover:bg-muted/50 cursor-pointer group"
            tabindex="0"
            @click="selectedUser = user"
            @keydown.enter="selectedUser = user"
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
            <td class="px-4 py-3 text-right text-muted-foreground group-hover:text-foreground">
              <ChevronRight class="h-4 w-4" />
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
              data-testid="form-username"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
            />
          </div>
          <!-- WO-UI-10 Phase 2: account type (HUMAN / SYSTEM). Immutable after creation. -->
          <div>
            <label class="block text-sm font-medium mb-1">{{ t('userType') }}</label>
            <Select v-model="formUserType" :disabled="!!editingUser" class="w-full">
              <SelectTrigger data-testid="userType" class="w-full px-3 py-2 border border-input rounded-md text-sm disabled:opacity-50">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="HUMAN" data-testid="userType-item-HUMAN">{{ t('userTypeHuman') }}</SelectItem>
                <SelectItem value="SYSTEM" data-testid="userType-item-SYSTEM">{{ t('userTypeSystem') }}</SelectItem>
              </SelectContent>
            </Select>
            <p v-if="formUserType === 'SYSTEM'" class="mt-1 text-xs text-muted-foreground">{{ t('systemAccountHint') }}</p>
            <p v-else-if="editingUser" class="mt-1 text-xs text-muted-foreground">{{ t('userTypeImmutableHint') }}</p>
          </div>
          <!-- WO-ACL-18: choose how the account is created (invitation link vs direct password). Hidden for SYSTEM. -->
          <div v-if="!editingUser && formUserType !== 'SYSTEM'">
            <label class="block text-sm font-medium mb-1">{{ t('creationModeLabel') }}</label>
            <Select v-model="formCreationMode" class="w-full">
              <SelectTrigger data-testid="creationMode" class="w-full px-3 py-2 border border-input rounded-md text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="INVITE">{{ t('invitationMode') }}</SelectItem>
                <SelectItem value="PASSWORD">{{ t('passwordMode') }}</SelectItem>
              </SelectContent>
            </Select>
            <p v-if="formCreationMode === 'INVITE'" class="mt-1 text-xs text-muted-foreground">
              {{ t('inviteHint') }}
            </p>
          </div>
          <div v-if="editingUser || (formUserType === 'HUMAN' && formCreationMode === 'PASSWORD')">
            <label class="block text-sm font-medium mb-1">{{ editingUser ? t('newPasswordOptional') : t('password') }}</label>
            <input
              v-model="formPassword"
              type="password"
              autocomplete="new-password"
              class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div v-else-if="formUserType === 'HUMAN' && formCreationMode === 'INVITE'" class="text-xs text-muted-foreground">
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
            <Select v-model="formRole" class="w-full">
              <SelectTrigger data-testid="create-role-trigger" class="w-full px-3 py-2 border border-input rounded-md text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="USER">{{ t('userRole') }}</SelectItem>
                <SelectItem value="ADMIN">{{ t('adminRole') }}</SelectItem>
                <SelectItem value="SUPER_ADMIN" data-testid="create-role-SUPER_ADMIN">{{ t('superAdminRole') }}</SelectItem>
              </SelectContent>
            </Select>
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
            data-testid="submit-user"
            @click="save"
          >
            {{ editingUser ? t('save') : t('add') }}
          </button>
        </div>
      </div>
    </div>

    <!-- User detail Drawer: a full user card. A row click opens it; the "edit account"
         action inside reuses the existing create/edit modal (no second modal invented). -->
    <Sheet :open="!!selectedUser" @update:open="(v) => { if (!v) { selectedUser = null; editing = false } }">
      <SheetContent
        side="right"
        data-testid="user-detail-drawer"
        :style="{ width: '600px', maxWidth: '90vw', padding: '0' }"
        class="flex flex-col"
      >
        <!-- Fixed header: compact identity hero, visually separated from the body -->
        <div class="shrink-0 border-b border-border px-4 py-3 pr-12">
          <SheetTitle class="text-lg font-bold truncate text-foreground">{{ selectedUser?.username }}</SheetTitle>
        </div>
        <!-- Scrollable body -->
        <div class="overflow-y-auto flex-1 p-5 space-y-6" data-testid="user-detail-body">
          <UserDetailPanel
            v-if="selectedUser"
            :user="selectedUser"
            :editing="editing"
            :edit-form="editForm"
            :saving="saving"
            @edit="startEdit"
            @save="saveEdit"
            @cancel="cancelEdit"
            @close="selectedUser = null"
          />
        </div>
      </SheetContent>
    </Sheet>
  </div>
</template>
