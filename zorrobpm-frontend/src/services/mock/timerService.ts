import type { TimerJob } from '@/types/api'

const MOCK_TIMERS: TimerJob[] = [
  {
    id: 't1',
    activityId: 'a1',
    processInstanceId: 'pi-1',
    dueAt: new Date(Date.now() + 3600000).toISOString(),
    fired: false,
    boundaryElementId: 'boundary1',
    eventSubprocessId: null,
    createdAt: new Date().toISOString(),
  },
  {
    id: 't2',
    activityId: 'a2',
    processInstanceId: 'pi-2',
    dueAt: new Date(Date.now() - 1800000).toISOString(),
    fired: true,
    boundaryElementId: null,
    eventSubprocessId: null,
    createdAt: new Date(Date.now() - 7200000).toISOString(),
  },
]

export async function getTimers(): Promise<TimerJob[]> {
  return MOCK_TIMERS
}
