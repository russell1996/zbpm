import api from './api'

/**
 * Process submission (WO-ACL-3): a user asks for a NEW process to be deployed,
 * a SUPER_ADMIN approves (submitter becomes OWNER) or rejects with a reason.
 */
export interface ProcessSubmission {
  id: string
  processKey: string | null
  name: string | null
  /** PENDING / APPROVED / REJECTED */
  status: string
  submittedBy: string
  /** WO-ACL-9: enriched submitter identity (null if user was deleted). */
  submittedByUsername: string | null
  submittedByFullName: string | null
  submittedByEmail: string | null
  submittedAt: string
  reviewedBy: string | null
  /** WO-ACL-9: reviewer username (null if not yet reviewed or user was deleted). */
  reviewedByUsername: string | null
  reviewedAt: string | null
  rejectReason: string | null
  approvedDefinitionId: string | null
  previousSubmissionId: string | null
}

/** Submit a new process for review — any authenticated user (WO-ACL-6 criterion 5). */
export async function submitProcessSubmission(bpmn: string): Promise<ProcessSubmission> {
  const { data } = await api.post<ProcessSubmission>('/process-submissions', { bpmn })
  return data
}

/** The current user's submissions, newest first. */
export async function getMySubmissions(): Promise<ProcessSubmission[]> {
  const { data } = await api.get<ProcessSubmission[]>('/process-submissions/mine')
  return data
}

/**
 * Review queue, oldest first. SUPER_ADMIN only.
 * WO-ACL-15 criterion 9: optional status filter (PENDING default | APPROVED |
 * REJECTED | SUPERSEDED | ALL) — the queue page shows history, not only the
 * pending pile (server support since WO-ACL-12).
 */
export async function getPendingSubmissions(status?: string): Promise<ProcessSubmission[]> {
  const { data } = await api.get<ProcessSubmission[]>('/process-submissions', {
    params: status ? { status } : {},
  })
  return data
}

/** Deploy the submitted BPMN and register the submitter as OWNER. SUPER_ADMIN only. */
export async function approveSubmission(id: string): Promise<ProcessSubmission> {
  const { data } = await api.post<ProcessSubmission>(`/process-submissions/${id}/approve`)
  return data
}

/** Decline the submission with a mandatory reason. SUPER_ADMIN only. */
export async function rejectSubmission(id: string, reason: string): Promise<ProcessSubmission> {
  const { data } = await api.post<ProcessSubmission>(`/process-submissions/${id}/reject`, { reason })
  return data
}

/** WO-ACL-10 criterion 13: raw BPMN of a submission — the reviewer sees the
 *  model BEFORE approving (endpoint exists since WO-ACL-7). SUPER_ADMIN only. */
export async function getSubmissionBpmn(id: string): Promise<string> {
  const { data } = await api.get<string>(`/process-submissions/${id}/bpmn`)
  return data
}
