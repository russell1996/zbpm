<script setup lang="ts">
/**
 * WO-ACL-14 criteria 9-13: adding a member happens in a DIALOG, not inline in
 * the members tab.
 *  9  — the "Add member" button opens this dialog; the inline search is gone;
 * 10  — results are a LIST of candidates (WO-ACL-15: fullName/email come from
 *       the contract — see the ACL-14 ESCALATION, resolved here), selected by
 *       clicking a row, not by typing an id;
 * 11  — candidates already in the members list are marked and cannot be
 *       selected again (alreadyMember);
 * 12  — the dialog closes on success, STAYS open on error with the reason;
 *       the submit button is locked from press to response (double-guarded:
 *       `if (addingMember) return` in the handler AND :disabled — P-46);
 * 13  — the button that opens this dialog is gated by canManageMembers on the
 *       page (super-admin/OWNER only).
 *  8  — WO-ACL-15 part B: the list is visible WITHOUT typing — an empty query
 *       returns the first page on open; typing narrows it; a full page is
 *       labelled ("first 20 shown") instead of being silently truncated.
 */
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { searchMemberCandidates, addMember, type Member, type MemberCandidate } from '@/services/adminService'
import { errorMessage } from '@/shared/lib/utils'

const props = defineProps<{
  open: boolean
  processKey: string
  members: Member[]
}>()
const emit = defineEmits<{
  close: []
  added: [member: Member]
}>()

const { t } = useI18n()
const toast = useToast()

const query = ref('')
const candidates = ref<MemberCandidate[]>([])
const candidatesLoading = ref(false)
const candidatesError = ref<string | null>(null)
const candidatesTruncated = ref(false)
const selectedUserId = ref('')
const role = ref('VIEWER')
const addingMember = ref(false)

const memberIds = computed(() => new Set(props.members.map((m) => m.userId)))

/**
 * WO-ACL-15 criterion 8: the dialog shows the list IMMEDIATELY on open — an empty
 * query is a valid request since part B (the server returns the first page).
 * Typing narrows it; the truncation line explains when the page was cut.
 */
watch(
  () => props.open,
  (open) => {
    if (open) void search()
  },
  { immediate: true },
)

async function search() {
  const q = query.value.trim()
  candidatesLoading.value = true
  candidatesError.value = null
  try {
    candidates.value = await searchMemberCandidates(props.processKey, q)
    // WO-ACL-15 criterion 8: the server caps the page at 20 — if we got exactly
    // the cap, there may be more; say so instead of silently truncating.
    candidatesTruncated.value = candidates.value.length === 20
  } catch (e) {
    candidates.value = []
    candidatesTruncated.value = false
    candidatesError.value = errorMessage(e, t('failedToLoadCandidates'))
  } finally {
    candidatesLoading.value = false
  }
}

function selectCandidate(c: MemberCandidate) {
  // criterion 11: already-added candidates are marked and cannot be re-selected
  if (memberIds.value.has(c.userId)) return
  selectedUserId.value = c.userId
}

/**
 * WO-ACL-15 criteria 4-5: the row shows the person's NAME first (falling back to
 * the username), and the username stays as a detail when the name is shown; the
 * email is appended when present. Accounts without name/email render the bare
 * username — no "null", no empty lines.
 */
function candidateDetail(c: MemberCandidate): string {
  const parts: string[] = []
  if (c.fullName) parts.push(c.username)
  if (c.email) parts.push(c.email)
  return parts.join(' · ')
}

async function submit() {
  // criterion 12: guard in the handler, not only :disabled (P-46)
  if (addingMember.value || !selectedUserId.value) return
  addingMember.value = true
  try {
    const member = await addMember(props.processKey, selectedUserId.value, role.value)
    toast.success(t('memberAdded'))
    emit('added', member)
    emit('close')
  } catch (e) {
    // stays open on error with the reason
    candidatesError.value = errorMessage(e, t('failedToAddMember'))
  } finally {
    addingMember.value = false
  }
}

function reset() {
  query.value = ''
  candidates.value = []
  candidatesError.value = null
  candidatesTruncated.value = false
  selectedUserId.value = ''
  role.value = 'VIEWER'
  addingMember.value = false
}
</script>

<template>
  <div v-if="open" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card rounded-lg shadow-xl w-full max-w-md mx-4 p-4 space-y-3">
      <h2 class="text-lg font-bold">{{ t('addMember') }}</h2>

      <input
        v-model="query"
        class="w-full px-2 py-1.5 border border-input rounded text-sm"
        :placeholder="t('searchCandidatePlaceholder')"
        @input="search"
      />

      <div v-if="candidatesLoading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
      <div v-else-if="candidatesError" class="text-sm text-red-500">{{ candidatesError }}</div>
      <div v-else-if="candidates.length" class="max-h-60 overflow-y-auto border border-border rounded divide-y divide-border">
        <button
          v-for="c in candidates"
          :key="c.userId"
          class="w-full flex items-center justify-between px-3 py-2 text-left text-sm hover:bg-muted transition-colors"
          :class="[selectedUserId === c.userId ? 'bg-sidebar-accent font-medium' : '', memberIds.has(c.userId) ? 'opacity-60 cursor-not-allowed' : '']"
          :disabled="memberIds.has(c.userId)"
          @click="selectCandidate(c)"
        >
          <span class="min-w-0">
            <span class="block truncate">{{ c.fullName || c.username }}</span>
            <span v-if="candidateDetail(c)" class="block text-xs text-muted-foreground truncate">{{ candidateDetail(c) }}</span>
          </span>
          <span v-if="memberIds.has(c.userId)" class="text-xs text-muted-foreground shrink-0">{{ t('alreadyMember') }}</span>
          <span v-else-if="selectedUserId === c.userId" class="text-xs text-muted-foreground shrink-0">{{ t('selected') }}</span>
        </button>
        <!-- WO-ACL-15 criterion 8: a full page may have more — say so, don't truncate silently -->
        <p v-if="candidatesTruncated" class="px-3 py-2 text-xs text-muted-foreground">{{ t('candidatesTruncated') }}</p>
      </div>
      <p v-else-if="query.trim().length > 0" class="text-sm text-muted-foreground">{{ t('noCandidates') }}</p>
      <p v-else class="text-sm text-muted-foreground">{{ t('searchCandidateHint') }}</p>

      <div v-if="selectedUserId" class="flex items-center gap-2">
        <select v-model="role" class="px-2 py-1 border border-input rounded text-xs">
          <option value="OWNER">{{ t('ownerRole') }}</option>
          <option value="DESIGNER">{{ t('designerRole') }}</option>
          <option value="VIEWER">{{ t('viewerRole') }}</option>
        </select>
        <span class="text-xs text-muted-foreground">{{ t('adding') }}</span>
      </div>

      <div class="flex justify-end gap-2 pt-1">
        <button
          class="px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          @click="emit('close')"
        >
          {{ t('cancel') }}
        </button>
        <button
          class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity disabled:opacity-50"
          :disabled="addingMember || !selectedUserId"
          @click="submit"
        >
          {{ addingMember ? t('adding') : t('addMember') }}
        </button>
      </div>
    </div>
  </div>
</template>