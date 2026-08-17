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
  submittedAt: string
  reviewedBy: string | null
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

/** PENDING review queue, oldest first. SUPER_ADMIN only. */
export async function getPendingSubmissions(): Promise<ProcessSubmission[]> {
  const { data } = await api.get<ProcessSubmission[]>('/process-submissions')
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
