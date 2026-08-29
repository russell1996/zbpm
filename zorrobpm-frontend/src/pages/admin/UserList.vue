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
/** WO-UI-10 Phase 2: account type — HUMAN (default) or SYSTEM (integration, API-key only). */
const formUserType = ref<'HUMAN' | 'SYSTEM'>('HUMAN')

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
  formUserType.value = 'HUMAN'
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
  formUserType.value = user.userType ?? 'HUMAN'
  showForm.value = true
}

async function save() {
  if (!formUsername.value) {
    toast.warning(t('fillRequired'))
    return
  }
  // WO-ACL-19 (P2) + WO-UI-10 Phase 2: a HUMAN account MUST have an email (every reset path is
  // email-driven). A SYSTEM account is an integration and has no email requirement.
  const requiresEmail = editingUser.value
    ? editingUser.value.userType === 'HUMAN'
    : formUserType.value === 'HUMAN'
  if (requiresEmail && !formEmail.value) {
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
        password: formUserType.value === 'SYSTEM' ? '' : (formCreationMode.value === 'PASSWORD' ? formPassword.value : ''),
        fullName: formFullName.value || null,
        email: formEmail.value || null,
        role: formRole.value,
        active: formActive.value,
        creationMode: formUserType.value === 'SYSTEM' ? undefined : formCreationMode.value,
        userType: formUserType.value,
      })
    }
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
  <div class="t9-font">
    <!-- Title bar — classic telecom console caption -->
    <div class="t9-titlebar px-3 py-2 mb-3 flex items-center justify-between">
      <span class="text-base">{{ t('users') }}</span>
      <button class="t9-btn t9-btn-primary px-3 py-1 text-sm" @click="openCreate">
        {{ t('addUser') }}
      </button>
    </div>

    <!-- Toolbar — filterable list, one screen (WO-INT-4) -->
    <div class="t9-panel px-3 py-2 mb-3 flex items-center gap-3">
      <input
        v-model="search"
        type="text"
        :placeholder="t('searchByUsername')"
        class="t9-input w-64"
        @input="loadUsers"
      />
      <select
        v-model="typeFilter"
        class="t9-input"
        data-testid="user-type-filter"
      >
        <option value="ALL">{{ t('filterAllUsers') }}</option>
        <option value="HUMAN">{{ t('filterHumanUsers') }}</option>
        <option value="SYSTEM">{{ t('filterSystemUsers') }}</option>
      </select>
      <span class="t9-count">{{ totalCount }} {{ t('usersCount') }}</span>
    </div>

    <div v-if="loading" class="t9-status">{{ t('loading') }}</div>

    <table v-else class="t9-table w-full text-sm">
      <thead>
        <tr>
          <th class="px-3 py-2">{{ t('username') }}</th>
          <th class="px-3 py-2">{{ t('fullName') }}</th>
          <th class="px-3 py-2">{{ t('email') }}</th>
          <th class="px-3 py-2">{{ t('role') }}</th>
          <th class="px-3 py-2">{{ t('status') }}</th>
          <th class="px-3 py-2">{{ t('actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="user in visibleUsers" :key="user.id">
          <tr
            class="t9-row"
            tabindex="0"
            @click="expandedUserId = expandedUserId === user.id ? null : user.id"
            @keydown.enter="expandedUserId = expandedUserId === user.id ? null : user.id"
          >
            <td class="px-3 py-2 t9-mono">
              {{ user.username }}
              <!-- WO-INT-4 criterion 2: a system account is an integration, not a person -->
              <span v-if="user.userType === 'SYSTEM'" class="t9-chip t9-chip-sys">{{ t('systemAccount') }}</span>
              <!-- WO-ACL-18 criterion 5: an outstanding invite token means the account is not yet active-with-password -->
              <span v-if="user.pendingInvitation" class="t9-chip t9-chip-inv" data-testid="pending-invitation-badge">{{ t('invited') }}</span>
            </td>
            <td class="px-3 py-2">{{ user.fullName || '—' }}</td>
            <td class="px-3 py-2">{{ user.email || '—' }}</td>
            <td class="px-3 py-2"><span class="t9-role">{{ user.role }}</span></td>
            <td class="px-3 py-2">
              <StatusBadge :status="user.active ? 'ACTIVE' : 'INACTIVE'" />
            </td>
            <td class="px-3 py-2">
              <div class="flex items-center gap-2">
                <button class="t9-link" @click.stop="openEdit(user)">{{ t('edit') }}</button>
                <button
                  class="t9-link"
                  :class="user.active ? 't9-link-danger' : 't9-link-ok'"
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
          <td colspan="6" class="t9-empty">{{ t('noUsers') }}</td>
        </tr>
      </tbody>
    </table>

    <!-- Create / edit window — classic bordered dialog -->
    <div
      v-if="showForm"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showForm = false"
    >
      <div class="t9-window w-full max-w-md">
        <div class="t9-titlebar px-3 py-2">{{ editingUser ? t('editUser') : t('addUser') }}</div>
        <div class="t9-window-body space-y-3">
          <div class="t9-field">
            <label class="t9-label">{{ t('username') }}</label>
            <input
              v-model="formUsername"
              type="text"
              :disabled="!!editingUser"
              data-testid="form-username"
              class="t9-input"
            />
          </div>
          <!-- WO-UI-10 Phase 2: account type (HUMAN / SYSTEM). Immutable after creation. -->
          <div class="t9-field">
            <label class="t9-label">{{ t('userType') }}</label>
            <select v-model="formUserType" :disabled="!!editingUser" data-testid="userType" class="t9-input">
              <option value="HUMAN">{{ t('userTypeHuman') }}</option>
              <option value="SYSTEM">{{ t('userTypeSystem') }}</option>
            </select>
            <p v-if="formUserType === 'SYSTEM'" class="t9-hint">{{ t('systemAccountHint') }}</p>
            <p v-else-if="editingUser" class="t9-hint">{{ t('userTypeImmutableHint') }}</p>
          </div>
          <!-- WO-ACL-18: choose how the account is created (invitation link vs direct password). Hidden for SYSTEM. -->
          <div v-if="!editingUser && formUserType !== 'SYSTEM'" class="t9-field">
            <label class="t9-label">{{ t('creationModeLabel') }}</label>
            <select v-model="formCreationMode" data-testid="creationMode" class="t9-input">
              <option value="INVITE">{{ t('invitationMode') }}</option>
              <option value="PASSWORD">{{ t('passwordMode') }}</option>
            </select>
            <p v-if="formCreationMode === 'INVITE'" class="t9-hint">{{ t('inviteHint') }}</p>
          </div>
          <div v-if="editingUser || (formUserType === 'HUMAN' && formCreationMode === 'PASSWORD')" class="t9-field">
            <label class="t9-label">{{ editingUser ? t('newPasswordOptional') : t('password') }}</label>
            <input
              v-model="formPassword"
              type="password"
              autocomplete="new-password"
              class="t9-input"
            />
          </div>
          <div v-else-if="formUserType === 'HUMAN' && formCreationMode === 'INVITE'" class="t9-hint">
            {{ t('inviteEmailNote') }}
          </div>
          <div class="t9-field">
            <label class="t9-label">{{ t('fullName') }}</label>
            <input v-model="formFullName" type="text" class="t9-input" />
          </div>
          <div class="t9-field">
            <label class="t9-label">{{ t('email') }}</label>
            <input v-model="formEmail" type="email" class="t9-input" />
          </div>
          <div class="t9-field">
            <label class="t9-label">{{ t('role') }}</label>
            <select v-model="formRole" class="t9-input">
              <option value="USER">{{ t('userRole') }}</option>
              <option value="ADMIN">{{ t('adminRole') }}</option>
              <option value="SUPER_ADMIN">{{ t('superAdminRole') }}</option>
            </select>
          </div>
          <div class="flex items-center gap-2">
            <input id="active" v-model="formActive" type="checkbox" />
            <label for="active" class="t9-label">{{ t('active') }}</label>
          </div>
        </div>
        <div class="t9-window-foot">
          <button class="t9-btn px-4 py-1 text-sm" @click="showForm = false">{{ t('cancel') }}</button>
          <button
            :disabled="saving"
            class="t9-btn t9-btn-primary px-4 py-1 text-sm"
            data-testid="submit-user"
            @click="save"
          >
            {{ editingUser ? t('save') : t('add') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 1999 telecom admin-console aesthetic (scoped to this page) */
.t9-font {
  font-family: Tahoma, Verdana, "MS Sans Serif", Geneva, sans-serif;
  color: #000;
}
.t9-mono {
  font-family: "Courier New", Courier, monospace;
}
.t9-titlebar {
  background: linear-gradient(90deg, #000080 0%, #1084d0 100%);
  color: #ffffff;
  font-weight: 700;
  letter-spacing: 0.3px;
}
.t9-panel {
  background: #d4d0c8;
  border: 2px solid;
  border-color: #ffffff #404040 #404040 #ffffff; /* outset bevel */
  padding: 6px 8px;
}
.t9-btn {
  background: #d4d0c8;
  border: 2px solid;
  border-color: #ffffff #404040 #404040 #ffffff;
  color: #000080;
  font-weight: 600;
  font-family: Tahoma, Verdana, "MS Sans Serif", sans-serif;
  cursor: pointer;
}
.t9-btn:active {
  border-color: #404040 #ffffff #ffffff #404040; /* inset on press */
}
.t9-btn:disabled {
  color: #808080;
  cursor: default;
}
.t9-btn-primary {
  background: linear-gradient(180deg, #1084d0 0%, #000080 100%);
  color: #ffffff;
  border-color: #ffffff #003a6c #003a6c #ffffff;
}
.t9-input {
  background: #ffffff;
  border: 2px solid;
  border-color: #808080 #ffffff #ffffff #808080; /* inset field */
  padding: 2px 4px;
  font-family: Tahoma, Verdana, "MS Sans Serif", sans-serif;
  font-size: 13px;
  color: #000;
}
.t9-input:focus {
  outline: 1px dotted #000080;
}
.t9-count {
  color: #000080;
  font-weight: 700;
  font-size: 13px;
}
.t9-status {
  color: #404040;
  font-size: 13px;
  padding: 8px 0;
}
.t9-table {
  border-collapse: collapse;
  background: #ffffff;
}
.t9-table th {
  background: linear-gradient(180deg, #1084d0 0%, #000080 100%);
  color: #ffffff;
  font-weight: 700;
  border: 1px solid #404040;
  text-align: left;
}
.t9-table td {
  border: 1px solid #a0a0a0;
}
.t9-row:nth-child(odd) {
  background: #f0f0f0;
}
.t9-row:hover {
  background: #cfe4ff;
}
.t9-row {
  cursor: pointer;
}
.t9-chip {
  display: inline-flex;
  align-items: center;
  margin-left: 6px;
  padding: 0 4px;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  border: 1px solid;
}
.t9-chip-sys {
  background: #fff2cc;
  border-color: #d9a300;
  color: #7a5c00;
}
.t9-chip-inv {
  background: #ffe0b3;
  border-color: #d9730a;
  color: #8a4b00;
}
.t9-role {
  display: inline-block;
  background: #dbeafc;
  border: 1px solid #7fb0e0;
  color: #0a3d6b;
  padding: 0 6px;
  font-size: 12px;
  font-weight: 600;
}
.t9-link {
  background: none;
  border: none;
  padding: 0;
  color: #000080;
  font-size: 13px;
  text-decoration: underline;
  cursor: pointer;
  font-family: Tahoma, Verdana, "MS Sans Serif", sans-serif;
}
.t9-link-danger { color: #aa0000; }
.t9-link-ok { color: #007a00; }
.t9-empty {
  text-align: center;
  color: #808080;
  padding: 16px 0;
}
.t9-window {
  background: #d4d0c8;
  border: 2px solid;
  border-color: #ffffff #404040 #404040 #ffffff;
  box-shadow: 3px 3px 0 rgba(0, 0, 0, 0.4);
}
.t9-window-body {
  background: #d4d0c8;
  padding: 10px 12px;
}
.t9-window-foot {
  background: #d4d0c8;
  padding: 8px 12px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  border-top: 1px solid #a0a0a0;
}
.t9-field {
  display: block;
}
.t9-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 2px;
  color: #000080;
}
.t9-hint {
  font-size: 11px;
  color: #404040;
  margin-top: 2px;
}
</style>
