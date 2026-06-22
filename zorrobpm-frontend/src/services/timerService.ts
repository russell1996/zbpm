import api from './api'
import type { TimerJob, PagedData, TimerJobQuery } from '@/types/api'

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return entries.length > 0 ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : ''
}

export async function getTimerJobs(query: TimerJobQuery = {}): Promise<PagedData<TimerJob>> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 20, ...query })
  const { data } = await api.get<PagedData<TimerJob>>(`/timer-jobs${qs}`)
  return data
}
