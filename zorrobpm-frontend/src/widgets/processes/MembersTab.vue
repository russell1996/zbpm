<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { Member } from '@/types/api'
import * as memberService from '@/services/memberService'

const props = defineProps<{ processKey: string }>()

const members = ref<Member[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const showAddForm = ref(false)
const newUserId = ref('')
const newRole = ref('DESIGNER')
const editingMember = ref<Member | null>(null)
const editRole = ref('')

async function loadMembers() {
  loading.value = true
  error.value = null
  try {
    members.value = await memberService.listMembers(props.processKey)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to load members'
  } finally {
    loading.value = false
  }
}

async function addMember() {
  if (!newUserId.value) return
  try {
    await memberService.addMember(props.processKey, { userId: newUserId.value, role: newRole.value })
    newUserId.value = ''
    newRole.value = 'DESIGNER'
    showAddForm.value = false
    await loadMembers()
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status
    error.value = status === 409 ? 'User is already a member' : 'Failed to add member'
  }
}

async function changeRole() {
  if (!editingMember.value) return
  try {
    await memberService.changeRole(props.processKey, editingMember.value.userId, { role: editRole.value })
    editingMember.value = null
    editRole.value = ''
    await loadMembers()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to change role'
  }
}

async function removeMember(member: Member) {
  try {
    await memberService.removeMember(props.processKey, member.userId)
    await loadMembers()
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status
    error.value = status === 409 ? 'Cannot remove the last OWNER' : 'Failed to remove member'
  }
}

function startEdit(member: Member) {
  editingMember.value = member
  editRole.value = member.role
}

onMounted(loadMembers)
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center justify-between">
      <h3 class="text-lg font-bold">Members</h3>
      <button
        class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90"
        @click="showAddForm = !showAddForm"
      >
        {{ showAddForm ? 'Cancel' : '+ Add Member' }}
      </button>
    </div>

    <div v-if="error" class="text-sm text-red-500 bg-red-50 dark:bg-red-900/20 px-3 py-2 rounded">{{ error }}</div>

    <!-- Add form -->
    <div v-if="showAddForm" class="border border-border rounded-lg p-4 bg-card space-y-3">
      <div class="grid grid-cols-2 gap-3">
        <input
          v-model="newUserId"
          placeholder="User ID"
          class="px-3 py-1.5 border border-input rounded text-sm"
        />
        <select v-model="newRole" class="px-3 py-1.5 border border-input rounded text-sm">
          <option value="OWNER">OWNER</option>
          <option value="DESIGNER">DESIGNER</option>
        </select>
      </div>
      <button
        class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
        :disabled="!newUserId"
        @click="addMember"
      >
        Add
      </button>
    </div>

    <!-- Members list -->
    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>

    <table v-else-if="members.length" class="w-full text-sm border border-border rounded-lg overflow-hidden">
      <thead class="bg-muted">
        <tr>
          <th class="px-4 py-2 text-left font-medium">User</th>
          <th class="px-4 py-2 text-left font-medium">Role</th>
          <th class="px-4 py-2 text-left font-medium">Added</th>
          <th class="px-4 py-2 text-right font-medium">Actions</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="m in members" :key="m.userId" class="border-t border-border">
          <td class="px-4 py-2 font-mono text-xs">{{ m.username || m.userId }}</td>
          <td class="px-4 py-2">
            <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium"
              :class="m.role === 'OWNER' ? 'bg-amber-100 text-amber-800' : 'bg-blue-100 text-blue-800'">
              {{ m.role }}
            </span>
          </td>
          <td class="px-4 py-2 text-muted-foreground text-xs">{{ m.addedAt ? new Date(m.addedAt).toLocaleDateString() : '—' }}</td>
          <td class="px-4 py-2 text-right space-x-2">
            <button class="text-xs text-primary hover:underline" @click="startEdit(m)">Edit</button>
            <button class="text-xs text-red-500 hover:underline" @click="removeMember(m)">Remove</button>
          </td>
        </tr>
      </tbody>
    </table>

    <p v-else class="text-sm text-muted-foreground">No members yet.</p>

    <!-- Edit role modal -->
    <div v-if="editingMember" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="editingMember = null">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-sm p-6 space-y-4">
        <h3 class="font-bold">Change Role</h3>
        <p class="text-sm text-muted-foreground">User: <span class="font-mono">{{ editingMember.username || editingMember.userId }}</span></p>
        <select v-model="editRole" class="w-full px-3 py-1.5 border border-input rounded text-sm">
          <option value="OWNER">OWNER</option>
          <option value="DESIGNER">DESIGNER</option>
        </select>
        <div class="flex justify-end gap-2">
          <button class="px-3 py-1.5 text-sm border border-border rounded-md" @click="editingMember = null">Cancel</button>
          <button class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md" @click="changeRole">Save</button>
        </div>
      </div>
    </div>
  </div>
</template>
